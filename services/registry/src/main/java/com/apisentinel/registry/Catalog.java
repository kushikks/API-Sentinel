package com.apisentinel.registry;

import com.apisentinel.openapi.OpenApiDocument;
import com.apisentinel.shared.Events;
import com.apisentinel.shared.Outbox;
import com.apisentinel.shared.Paging;
import com.apisentinel.shared.Problem;
import com.apisentinel.shared.SecurityPolicy;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Catalog {
    private static final RowMapper<Api> API = (rs, row) -> new Api(id(rs, "id"), rs.getString("name"),
            rs.getString("description"), id(rs, "owner_id"), rs.getString("base_url"),
            time(rs, "created_at"), time(rs, "updated_at"));
    private static final RowMapper<Version> VERSION = (rs, row) -> new Version(id(rs, "id"), id(rs, "api_id"),
            rs.getString("version_label"), rs.getString("specification_hash"), time(rs, "created_at"),
            id(rs, "created_by"), rs.getString("status"), rs.getString("content_type"), rs.getInt("size_bytes"));
    private static final RowMapper<Submission> SUBMISSION = (rs, row) -> new Submission(id(rs, "id"),
            id(rs, "api_id"), id(rs, "version_id"), id(rs, "baseline_version_id"), id(rs, "correlation_id"),
            time(rs, "created_at"));
    private static final String VERSIONS = """
            select v.*, a.content_type, a.size_bytes from api_versions v
            join specification_artifacts a on a.version_id = v.id
            """;

    private final JdbcClient db;
    private final Outbox outbox;

    Catalog(JdbcClient db, Outbox outbox) {
        this.db = db;
        this.outbox = outbox;
    }

    @Transactional
    Api create(Jwt user, String name, String description, String baseUrl) {
        var id = UUID.randomUUID();
        int inserted = db.sql("""
                insert into apis (id, owner_id, name, description, base_url)
                values (:id, :owner, :name, :description, :url) on conflict do nothing
                """).param("id", id).param("owner", SecurityPolicy.actor(user)).param("name", apiName(name))
                .param("description", optionalText(description)).param("url", url(baseUrl)).update();
        if (inserted == 0) throw duplicateApi();
        return metadata(id);
    }

    Paging.Page<Api> list(Jwt user, String query, Paging page) {
        var rows = db.sql("""
                select * from apis
                where (:admin or owner_id = :owner)
                  and (strpos(lower(name), lower(:query)) > 0
                       or strpos(lower(coalesce(description, '')), lower(:query)) > 0)
                order by created_at desc, id desc limit :limit offset :offset
                """).param("admin", SecurityPolicy.hasRole(user, "ADMIN")).param("owner", SecurityPolicy.actor(user))
                .param("query", query.strip()).param("limit", page.size() + 1).param("offset", page.offset())
                .query(API).list();
        return page.slice(rows);
    }

    Api get(Jwt user, UUID api) { return owned(user, api, false); }

    @Transactional
    Api update(Jwt user, UUID api, String name, String description, String baseUrl) {
        var previous = owned(user, api, true);
        try {
            db.sql("""
                    update apis set name = :name, description = :description, base_url = :url, updated_at = now()
                    where id = :id
                    """).param("id", api).param("name", name == null ? previous.name() : apiName(name))
                    .param("description", description == null ? previous.description() : optionalText(description))
                    .param("url", baseUrl == null ? previous.baseUrl() : url(baseUrl)).update();
        } catch (org.springframework.dao.DuplicateKeyException error) {
            throw duplicateApi();
        }
        return metadata(api);
    }

    @Transactional
    Upload upload(Jwt user, UUID apiId, String label, byte[] content, OpenApiDocument.Format format, boolean analyze) {
        var api = owned(user, apiId, true);
        var version = UUID.randomUUID();
        var checksum = hash(content);
        int inserted = db.sql("""
                insert into api_versions (id, api_id, version_label, specification_hash, created_by)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """).params(version, apiId, label.strip(), checksum, SecurityPolicy.actor(user)).update();
        if (inserted == 0) throw new Problem(HttpStatus.CONFLICT, "VERSION_EXISTS", "A version already uses this label.");
        db.sql("""
                insert into specification_artifacts (id, version_id, content, content_type, size_bytes, checksum)
                values (?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), version, content, format.mediaType(), content.length, checksum).update();
        var submission = analyze ? submitLocked(user, api, version, null) : null;
        return new Upload(version(apiId, version), submission);
    }

    Paging.Page<Version> versions(Jwt user, UUID apiId, Paging page) {
        get(user, apiId);
        return page.slice(db.sql(VERSIONS + """
                where v.api_id = ? order by v.upload_order desc limit ? offset ?
                """).params(apiId, page.size() + 1, page.offset()).query(VERSION).list());
    }

    Version version(Jwt user, UUID apiId, UUID version) {
        get(user, apiId);
        return version(apiId, version);
    }

    Artifact spec(Jwt user, UUID apiId, UUID version) {
        get(user, apiId);
        return spec(apiId, version);
    }

    @Transactional
    Submission submit(Jwt user, UUID api, UUID version, UUID baseline) {
        return submitLocked(user, owned(user, api, true), version, baseline);
    }

    Submission latest(Jwt user, UUID api, UUID version) {
        version(user, api, version);
        return db.sql("""
                select * from analysis_requests where api_id = ? and version_id = ?
                order by submission_order desc limit 1
                """).params(api, version).query(SUBMISSION).optional()
                .orElseThrow(() -> notFound("ANALYSIS_NOT_SUBMITTED", "This version has no analysis submission."));
    }

    Api metadata(UUID api) {
        return db.sql("select * from apis where id = ?").param(api).query(API).optional()
                .orElseThrow(() -> notFound("API_NOT_FOUND", "The API was not found."));
    }

    Artifact spec(UUID api, UUID version) {
        return db.sql("""
                select a.content, a.content_type from specification_artifacts a
                join api_versions v on v.id = a.version_id where v.api_id = ? and v.id = ?
                """).params(api, version).query((rs, row) -> new Artifact(rs.getBytes("content"),
                rs.getString("content_type"))).optional()
                .orElseThrow(() -> notFound("VERSION_NOT_FOUND", "The version was not found."));
    }

    private Submission submitLocked(Jwt user, Api api, UUID version, UUID baseline) {
        version(api.id(), version);
        if (version.equals(baseline)) {
            throw new Problem(HttpStatus.BAD_REQUEST, "INVALID_BASELINE", "Choose a different version for the baseline.");
        }
        UUID baselineId = baseline;
        if (baselineId != null) {
            version(api.id(), baselineId);
        } else {
            baselineId = db.sql("""
                    select previous.id from api_versions previous join api_versions current
                    on previous.api_id = current.api_id and previous.upload_order < current.upload_order
                    where current.id = ? order by previous.upload_order desc limit 1
                    """).param(version).query(UUID.class).optional().orElse(null);
        }
        var existing = db.sql("""
                select * from analysis_requests where api_id = :api and version_id = :version
                    and baseline_version_id is not distinct from cast(:baseline as uuid)
                """).param("api", api.id()).param("version", version).param("baseline", baselineId)
                .query(SUBMISSION).optional();
        if (existing.isPresent()) return existing.get();
        UUID analysis = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID actor = SecurityPolicy.actor(user);
        var submission = db.sql("""
                insert into analysis_requests (id, api_id, version_id, baseline_version_id, submitted_by, correlation_id)
                values (:id, :api, :version, :baseline, :actor, :correlation) returning *
                """).param("id", analysis).param("api", api.id()).param("version", version).param("baseline", baselineId)
                .param("actor", actor).param("correlation", correlation).query(SUBMISSION).single();
        var payload = new Events.VersionRegistered(analysis, api.id(), version, baselineId, api.ownerId(), actor);
        outbox.append(Events.REGISTERED_ROUTE, new Events.Envelope<>(UUID.randomUUID(), 1, "VERSION_REGISTERED",
                correlation, version, submission.createdAt(), "registry-service", payload));
        return submission;
    }

    private Api owned(Jwt user, UUID api, boolean lock) {
        String query = """
                select * from apis where id = ? and (owner_id = ? or ?)
                """ + (lock ? " for update" : "");
        return db.sql(query).params(api, SecurityPolicy.actor(user), SecurityPolicy.hasRole(user, "ADMIN"))
                .query(API).optional().orElseThrow(() -> notFound("API_NOT_FOUND", "The API was not found."));
    }

    private Version version(UUID api, UUID version) {
        return db.sql(VERSIONS + " where v.api_id = ? and v.id = ?").params(api, version).query(VERSION).optional()
                .orElseThrow(() -> notFound("VERSION_NOT_FOUND", "The version was not found."));
    }

    private static String apiName(String name) {
        if (name.isBlank()) throw new Problem(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Enter an API name.");
        return name.strip();
    }

    private static String optionalText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static String url(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var uri = URI.create(value.strip());
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null) return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            // Return the same validation message for every malformed URL.
        }
        throw new Problem(HttpStatus.BAD_REQUEST, "INVALID_BASE_URL", "Enter an HTTP(S) base URL without credentials, query or fragment.");
    }

    private static Problem notFound(String code, String message) { return new Problem(HttpStatus.NOT_FOUND, code, message); }
    private static Problem duplicateApi() {
        return new Problem(HttpStatus.CONFLICT, "API_EXISTS", "An API with this name already exists for this owner.");
    }

    private static UUID id(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Api(UUID id, String name, String description, UUID ownerId, String baseUrl, Instant createdAt, Instant updatedAt) {}
    record Version(UUID id, UUID apiId, String versionLabel, String specificationHash, Instant createdAt,
                   UUID createdBy, String status, String contentType, int sizeBytes) {}
    record Artifact(byte[] content, String contentType) {}
    record Upload(Version version, Submission analysis) {}
    record Submission(UUID analysisId, UUID apiId, UUID newVersionId, UUID baselineVersionId, UUID correlationId, Instant createdAt) {}
}
