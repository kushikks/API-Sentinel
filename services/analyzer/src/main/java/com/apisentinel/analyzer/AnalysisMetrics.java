package com.apisentinel.analyzer;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AnalysisMetrics {
    private final JdbcClient db;
    private final AtomicLong pendingJobs = new AtomicLong();
    private final AtomicLong pendingEvents = new AtomicLong();

    AnalysisMetrics(JdbcClient db, MeterRegistry metrics) {
        this.db = db;
        metrics.gauge("sentinel.analysis.pending.jobs", pendingJobs);
        metrics.gauge("sentinel.analysis.pending.events", pendingEvents);
    }

    @Scheduled(fixedDelay = 10_000)
    void sample() {
        pendingJobs.set(db.sql("select count(*) from analysis_jobs where status in ('QUEUED','RETRY_PENDING')")
                .query(Long.class).single());
        pendingEvents.set(db.sql("select count(*) from outbox_events where published_at is null")
                .query(Long.class).single());
    }
}
