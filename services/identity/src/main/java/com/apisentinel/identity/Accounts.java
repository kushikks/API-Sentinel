package com.apisentinel.identity;

import com.apisentinel.shared.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Accounts {
    private final JdbcClient db;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final String dummyPassword;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    Accounts(JdbcClient db, PasswordEncoder passwords, JwtEncoder encoder,
             @Value("${sentinel.jwt.issuer}") String issuer,
             @Value("${sentinel.jwt.audience}") String audience,
             @Value("${sentinel.jwt.key-id}") String keyId) {
        this.db = db;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
        dummyPassword = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    Profile register(String email, String password, String displayName, String role) {
        if (!List.of("API_OWNER", "CONSUMER").contains(role)) {
            throw new Problem(HttpStatus.FORBIDDEN, "ROLE_NOT_ASSIGNABLE", "Choose API_OWNER or CONSUMER.");
        }
        if (displayName.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new Problem(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                    "Enter a display name and a password no longer than 72 UTF-8 bytes.");
        }
        var id = UUID.randomUUID();
        var normalizedEmail = normalize(email);
        int inserted = db.sql("""
                insert into users (id, email, display_name, password_hash) values (?, ?, ?, ?)
                on conflict (email) do nothing
                """).params(id, normalizedEmail, displayName.strip(), passwords.encode(password)).update();
        if (inserted == 0) throw new Problem(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This account already exists.");
        db.sql("insert into user_roles (user_id, role) values (?, ?)").params(id, role).update();
        audit(id, "REGISTERED");
        return profile(id);
    }

    @Transactional(noRollbackFor = Problem.class)
    Session login(String email, String password) {
        var account = db.sql("select id, password_hash from users where email = ?").param(normalize(email))
                .query((rs, row) -> new StoredAccount(rs.getObject("id", UUID.class), rs.getString("password_hash")))
                .optional();
        var hash = account.map(StoredAccount::hash).orElse(dummyPassword);
        boolean valid = password.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(password, hash);
        if (!valid || account.isEmpty()) {
            audit(account.map(StoredAccount::id).orElse(null), "LOGIN_REJECTED");
            throw new Problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "The email or password is incorrect.");
        }
        UUID user = account.get().id();
        UUID session = UUID.randomUUID();
        Instant expires = clock.instant().plusSeconds(7 * 24 * 60 * 60);
        db.sql("insert into refresh_sessions (id, user_id, expires_at) values (?, ?, ?)")
                .params(session, user, timestamp(expires)).update();
        audit(user, "LOGIN");
        return issue(user, session, expires);
    }

    @Transactional(noRollbackFor = Problem.class)
    Session refresh(String token) {
        var session = lockSession(token);
        if (session == null || session.revoked() || !clock.instant().isBefore(session.expires())) {
            throw invalidRefresh();
        }
        if (session.used()) {
            revoke(session.id());
            audit(session.user(), "REFRESH_REUSE");
            throw invalidRefresh();
        }
        db.sql("update refresh_tokens set used_at = now() where token_hash = ?")
                .param(hash(token)).update();
        return issue(session.user(), session.id(), session.expires());
    }

    @Transactional
    void logout(String token) {
        var session = lockSession(token);
        if (session != null && !session.revoked()) {
            revoke(session.id());
            audit(session.user(), "LOGOUT");
        }
    }

    Profile profile(UUID id) {
        var user = db.sql("select email, display_name from users where id = ?").param(id)
                .query((rs, row) -> new String[] {rs.getString("email"), rs.getString("display_name")}).optional()
                .orElseThrow(() -> new Problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "The account does not exist."));
        var roles = db.sql("select role from user_roles where user_id = ? order by role").param(id).query(String.class).list();
        return new Profile(id, user[0], user[1], roles);
    }

    private Session issue(UUID user, UUID session, Instant expires) {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        var refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.sql("insert into refresh_tokens (token_hash, session_id) values (?, ?)")
                .params(hash(refresh), session).update();
        var profile = profile(user);
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(user.toString())
                .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("roles", profile.roles()).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        var jwt = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new Session(jwt.getTokenValue(), refresh, Math.max(0, expires.getEpochSecond() - now.getEpochSecond()), profile);
    }

    private LockedSession lockSession(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        // Read token state after locking the family so concurrent rotation observes the committed used_at.
        var session = db.sql("""
                select s.id, s.user_id, s.expires_at, s.revoked_at is not null as revoked
                from refresh_sessions s join refresh_tokens t on t.session_id = s.id
                where t.token_hash = ? for update of s
                """).param(hash(token)).query((rs, row) -> new SessionFamily(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("revoked"))).optional();
        if (session.isEmpty()) return null;
        var family = session.get();
        boolean used = db.sql("select used_at is not null from refresh_tokens where token_hash = ?")
                .param(hash(token)).query(Boolean.class).single();
        return new LockedSession(family.id(), family.user(), family.expires(), family.revoked(), used);
    }

    private void revoke(UUID session) {
        db.sql("update refresh_sessions set revoked_at = now() where id = ?").param(session).update();
    }

    private void audit(UUID user, String action) {
        db.sql("insert into authentication_audit (user_id, action) values (:user, :action)")
                .param("user", user).param("action", action).update();
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static OffsetDateTime timestamp(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    private static Problem invalidRefresh() {
        return new Problem(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH", "Sign in again to continue.");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Profile(UUID id, String email, String displayName, List<String> roles) {}
    record Session(String accessToken, String refreshToken, long cookieSeconds, Profile profile) {}
    private record StoredAccount(UUID id, String hash) {}
    private record SessionFamily(UUID id, UUID user, Instant expires, boolean revoked) {}
    private record LockedSession(UUID id, UUID user, Instant expires, boolean revoked, boolean used) {}
}
