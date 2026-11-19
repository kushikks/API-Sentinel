package com.apisentinel.identity;

import com.apisentinel.shared.Problem;
import com.apisentinel.shared.SecurityPolicy;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
class AuthController {
    private static final String REFRESH_COOKIE = "sentinel_refresh";
    private static final String CSRF_HEADER = "X-Sentinel-CSRF";

    private final Accounts accounts;
    private final RSAKey signingKey;
    private final boolean secureCookie;

    AuthController(Accounts accounts, RSAKey signingKey, @Value("${sentinel.secure-cookie:true}") boolean secureCookie) {
        this.accounts = accounts;
        this.signingKey = signingKey;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    ResponseEntity<Accounts.Profile> register(@Valid @RequestBody Registration request) {
        String role = request.role() == null ? "CONSUMER" : request.role();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(accounts.register(request.email(), request.password(), request.displayName(), role));
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@RequestHeader(value = CSRF_HEADER, required = false) String csrf,
                                        @Valid @RequestBody Login request) {
        checkCsrf(csrf);
        return session(accounts.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(@RequestHeader(value = CSRF_HEADER, required = false) String csrf,
                                          @CookieValue(value = REFRESH_COOKIE, required = false) String refresh) {
        checkCsrf(csrf);
        return session(accounts.refresh(refresh));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestHeader(value = CSRF_HEADER, required = false) String csrf,
                                @CookieValue(value = REFRESH_COOKIE, required = false) String refresh) {
        checkCsrf(csrf);
        accounts.logout(refresh);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0))
                .cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    ResponseEntity<Accounts.Profile> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.profile(SecurityPolicy.actor(jwt)));
    }

    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(new JWKSet(signingKey.toPublicJWK()).toJSONObject());
    }

    private ResponseEntity<TokenResponse> session(Accounts.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), session.cookieSeconds()))
                .cacheControl(CacheControl.noStore()).body(new TokenResponse(session.accessToken(), 600, session.profile()));
    }

    private String cookie(String token, long lifetime) {
        return ResponseCookie.from(REFRESH_COOKIE, token).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/auth").maxAge(lifetime).build().toString();
    }

    private static void checkCsrf(String value) {
        if (!"1".equals(value)) {
            throw new Problem(HttpStatus.FORBIDDEN, "CSRF_HEADER_REQUIRED", "The session request requires X-Sentinel-CSRF.");
        }
    }

    record Registration(@Email @NotBlank @Size(max = 254) String email,
                        @NotBlank @Size(min = 12, max = 72) String password,
                        @NotBlank @Size(max = 100) String displayName,
                        @Size(max = 20) String role) {}
    record Login(@Email @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    record TokenResponse(String accessToken, int expiresIn, Accounts.Profile user) {}
}
