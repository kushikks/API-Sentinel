package com.apisentinel.analyzer;

import com.apisentinel.openapi.Compatibility;
import com.apisentinel.shared.Paging;
import com.apisentinel.shared.Problem;
import com.apisentinel.shared.SecurityPolicy;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
class AnalysisQueries {
    private final JdbcClient db;

    AnalysisQueries(JdbcClient db) { this.db = db; }

    Paging.Page<JobStore.Job> list(Jwt user, UUID api, JobStore.State status, Paging page) {
        var jobs = db.sql("""
                select * from analysis_jobs where (:admin or owner_id = :owner)
                    and (cast(:api as uuid) is null or api_id = :api)
                    and (cast(:status as text) is null or status = :status)
                order by created_at desc, id desc limit :limit offset :offset
                """).param("admin", SecurityPolicy.hasRole(user, "ADMIN")).param("owner", SecurityPolicy.actor(user))
                .param("api", api).param("status", status == null ? null : status.name())
                .param("limit", page.size() + 1).param("offset", page.offset()).query(JobStore.JOB).list();
        return page.slice(jobs);
    }

    JobStore.Job get(Jwt user, UUID id) {
        return db.sql("select * from analysis_jobs where id = ? and (owner_id = ? or ?)")
                .params(id, SecurityPolicy.actor(user), SecurityPolicy.hasRole(user, "ADMIN"))
                .query(JobStore.JOB).optional().orElseThrow(JobStore::missing);
    }

    JobStore.Job internal(UUID id) {
        return db.sql("select * from analysis_jobs where id = ?").param(id)
                .query(JobStore.JOB).optional().orElseThrow(JobStore::missing);
    }

    Paging.Page<JobStore.Finding> findings(JobStore.Job job, Compatibility.Severity severity, String category, Paging page) {
        var rows = db.sql("""
                select * from analysis_findings where analysis_id = :id
                    and (cast(:severity as text) is null or severity = :severity)
                    and (cast(:category as text) is null or category = :category)
                order by ordinal limit :limit offset :offset
                """).param("id", job.id()).param("severity", severity == null ? null : severity.name()).param("category", category)
                .param("limit", page.size() + 1).param("offset", page.offset())
                .query((rs, row) -> new JobStore.Finding(JobStore.id(rs, "id"), job.id(),
                        Compatibility.Severity.valueOf(rs.getString("severity")), rs.getString("category"),
                        rs.getString("http_method"), rs.getString("endpoint_path"), rs.getString("json_path"),
                        rs.getString("description"), rs.getString("old_value"), rs.getString("new_value"),
                        rs.getString("category"))).list();
        return page.slice(rows);
    }

    byte[] report(JobStore.Job job) {
        if (job.status() != JobStore.State.COMPLETED) {
            throw new Problem(HttpStatus.CONFLICT, "REPORT_PENDING", "A report is available after the analysis completes.");
        }
        return db.sql("select report from analysis_artifacts where analysis_id = ?").param(job.id())
                .query((rs, row) -> rs.getBytes(1)).optional().orElseThrow(JobStore::missing);
    }

    static Summary summary(JobStore.Job job) {
        return new Summary(Compatibility.REPORT_SCHEMA_VERSION, job.id(), job.status(), job.outcome(),
                job.breakingCount(), job.potentiallyBreakingCount(), job.nonBreakingCount());
    }

    static Status status(JobStore.Job job) {
        return new Status(job.status(), job.attemptCount(), job.startedAt(), job.completedAt(), job.error());
    }

    record Summary(int schemaVersion, UUID analysisId, JobStore.State status, String outcome, int breakingCount,
                   int potentiallyBreakingCount, int nonBreakingCount) {}
    record Status(JobStore.State status, int attemptCount, Instant startedAt, Instant completedAt, String error) {}
}
