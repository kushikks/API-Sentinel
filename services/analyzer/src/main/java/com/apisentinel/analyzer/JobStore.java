package com.apisentinel.analyzer;

import com.apisentinel.openapi.Compatibility;
import com.apisentinel.shared.Events;
import com.apisentinel.shared.Outbox;
import com.apisentinel.shared.Problem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobStore {
    static final int MAX_ATTEMPTS = 8;
    static final RowMapper<Job> JOB = (rs, row) -> new Job(id(rs, "id"), id(rs, "api_id"), id(rs, "baseline_version_id"),
            id(rs, "new_version_id"), id(rs, "owner_id"), id(rs, "submitted_by"), id(rs, "correlation_id"),
            State.valueOf(rs.getString("status")), rs.getInt("attempt_count"), time(rs, "created_at"),
            time(rs, "started_at"), time(rs, "completed_at"), rs.getString("error"), rs.getString("outcome"),
            rs.getInt("breaking_count"), rs.getInt("potentially_breaking_count"), rs.getInt("non_breaking_count"));

    private final JdbcClient db;
    private final JdbcTemplate batch;
    private final Outbox outbox;
    private final ObjectMapper json;

    JobStore(JdbcClient db, JdbcTemplate batch, Outbox outbox, ObjectMapper json) {
        this.db = db;
        this.batch = batch;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    void ingest(Events.Envelope<Events.VersionRegistered> event) {
        int inserted = db.sql("insert into processed_events (id) values (?) on conflict do nothing")
                .param(event.eventId()).update();
        if (inserted == 0) return;
        var request = event.payload();
        db.sql("""
                insert into analysis_jobs(id, api_id, baseline_version_id, new_version_id, owner_id, submitted_by, correlation_id)
                values (:id, :api, :baseline, :next, :owner, :actor, :correlation) on conflict do nothing
                """).param("id", request.analysisId()).param("api", request.apiId()).param("baseline", request.baselineVersionId())
                .param("next", request.versionId()).param("owner", request.ownerId()).param("actor", request.submittedBy())
                .param("correlation", event.correlationId()).update();
        var durable = db.sql("""
                select id, owner_id from analysis_jobs where api_id = :api and new_version_id = :version
                    and baseline_version_id is not distinct from cast(:baseline as uuid)
                """).param("api", request.apiId()).param("version", request.versionId()).param("baseline", request.baselineVersionId())
                .query((rs, row) -> new Existing(id(rs, "id"), id(rs, "owner_id"))).optional();
        if (durable.isEmpty() || !durable.get().id().equals(request.analysisId())
                || !durable.get().owner().equals(request.ownerId())) {
            throw new IllegalArgumentException("The event conflicts with a durable analysis identity.");
        }
    }

    @Transactional
    Work claim() {
        var lease = UUID.randomUUID();
        return db.sql("""
                with candidate as (
                  select id from analysis_jobs
                  where attempt_count < :attempts and (
                    (status in ('QUEUED','RETRY_PENDING') and next_attempt_at <= now())
                    or (status = 'RUNNING' and lease_until <= now())
                  )
                  order by next_attempt_at, id limit 1 for update skip locked
                )
                update analysis_jobs j
                set status = 'RUNNING', attempt_count = attempt_count + 1, lease_token = :lease,
                    lease_until = now() + interval '90 seconds', started_at = coalesce(started_at, now()), error = null
                from candidate where j.id = candidate.id returning j.*
                """).param("attempts", MAX_ATTEMPTS).param("lease", lease).query(JOB).optional()
                .map(job -> new Work(job, lease)).orElse(null);
    }

    int expireExhausted() {
        return db.sql("""
                update analysis_jobs set status = 'FAILED', completed_at = now(),
                    error = 'The last worker lease expired.', lease_token = null, lease_until = null
                where status = 'RUNNING' and lease_until <= now() and attempt_count >= ?
                """).param(MAX_ATTEMPTS).update();
    }

    @Transactional
    boolean complete(Work work, Compatibility.Result result) {
        var job = work.job();
        List<Finding> findings = new ArrayList<>(result.findings().size());
        int breaking = 0;
        int potential = 0;
        int nonBreaking = 0;
        for (var change : result.findings()) {
            switch (change.severity()) {
                case BREAKING -> breaking++;
                case POTENTIALLY_BREAKING -> potential++;
                case NON_BREAKING -> nonBreaking++;
            }
            findings.add(new Finding(UUID.randomUUID(), job.id(), change.severity(), change.category(), change.httpMethod(),
                    change.path(), change.jsonPath(), change.description(), change.oldValue(), change.newValue(), change.category()));
        }
        var completed = db.sql("""
                update analysis_jobs set status = 'COMPLETED', completed_at = now(), lease_token = null, lease_until = null,
                    outcome = ?, breaking_count = ?, potentially_breaking_count = ?, non_breaking_count = ?
                where id = ? and status = 'RUNNING' and lease_token = ? and lease_until > now() returning *
                """).params(result.outcome(), breaking, potential, nonBreaking, job.id(), work.lease()).query(JOB).optional();
        if (completed.isEmpty()) return false;
        saveFindings(job.id(), findings);
        var report = new Report(result.schemaVersion(), completed.get(), List.copyOf(findings));
        db.sql("insert into analysis_artifacts (analysis_id, schema_version, report) values (?, ?, ?)")
                .params(job.id(), result.schemaVersion(), encode(report)).update();
        var payload = new Events.AnalysisCompleted(job.id(), job.apiId(), job.baselineVersionId(), job.newVersionId(),
                State.COMPLETED.name(), breaking, potential, nonBreaking);
        outbox.append(Events.COMPLETED_ROUTE, new Events.Envelope<>(UUID.randomUUID(), 1, "ANALYSIS_COMPLETED",
                job.correlationId(), job.id(), completed.get().completedAt(), "analyzer-service", payload));
        return true;
    }

    @Transactional
    State fail(Work work, String error, boolean retryable) {
        var job = work.job();
        boolean retry = retryable && job.attemptCount() < MAX_ATTEMPTS;
        var state = retry ? State.RETRY_PENDING : State.FAILED;
        int changed = db.sql("""
                update analysis_jobs set status = :state, error = :error, lease_token = null, lease_until = null,
                    completed_at = case when :retry then null else now() end,
                    next_attempt_at = now() + (:delay * interval '1 second')
                where id = :id and status = 'RUNNING' and lease_token = :lease and lease_until > now()
                """).param("state", state.name()).param("error", error).param("retry", retry)
                .param("delay", 1 << job.attemptCount()).param("id", job.id()).param("lease", work.lease()).update();
        return changed == 0 ? null : state;
    }

    private void saveFindings(UUID analysis, List<Finding> findings) {
        if (findings.isEmpty()) return;
        var rows = new ArrayList<Object[]>(findings.size());
        for (int i = 0; i < findings.size(); i++) {
            var finding = findings.get(i);
            rows.add(new Object[] {finding.findingId(), analysis, i, finding.severity().name(), finding.category(),
                    finding.httpMethod(), finding.path(), finding.jsonPath(), finding.description(), finding.oldValue(), finding.newValue()});
        }
        batch.batchUpdate("""
                insert into analysis_findings (id, analysis_id, ordinal, severity, category, http_method, endpoint_path,
                    json_path, description, old_value, new_value) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private byte[] encode(Report report) {
        try {
            return json.writeValueAsBytes(report);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize the compatibility report.", error);
        }
    }

    static Problem missing() {
        return new Problem(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND", "The analysis was not found.");
    }

    static UUID id(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    static Instant time(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    enum State { QUEUED, RUNNING, RETRY_PENDING, COMPLETED, FAILED }
    record Work(Job job, UUID lease) {}
    record Job(UUID id, UUID apiId, UUID baselineVersionId, UUID newVersionId, UUID ownerId, UUID submittedBy,
               UUID correlationId, State status, int attemptCount, Instant createdAt, Instant startedAt,
               Instant completedAt, String error, String outcome, int breakingCount, int potentiallyBreakingCount,
               int nonBreakingCount) {}
    record Finding(UUID findingId, UUID analysisId, Compatibility.Severity severity, String category,
                   String httpMethod, String path, String jsonPath, String description, String oldValue,
                   String newValue, String ruleId) {}
    record Report(int schemaVersion, Job analysis, List<Finding> findings) {}
    private record Existing(UUID id, UUID owner) {}
}
