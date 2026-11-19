package com.apisentinel.analyzer;

import com.apisentinel.openapi.Compatibility;
import com.apisentinel.openapi.OpenApiDocument;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "sentinel.worker-enabled", matchIfMissing = true)
class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

    private final JobStore jobs;
    private final RegistryClient registry;
    private final MeterRegistry metrics;
    private final Timer processing;
    private final ExecutorService pool;
    private final Semaphore slots;

    AnalysisWorker(JobStore jobs, RegistryClient registry, MeterRegistry metrics,
                   @Value("${sentinel.worker-concurrency}") int concurrency) {
        if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("Worker concurrency must be between 1 and 8.");
        this.jobs = jobs;
        this.registry = registry;
        this.metrics = metrics;
        this.processing = metrics.timer("sentinel.analysis.processing");
        this.pool = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().name("analysis-worker-", 0).factory());
        this.slots = new Semaphore(concurrency);
    }

    @Scheduled(fixedDelayString = "${sentinel.worker-poll-delay:300}")
    void schedule() {
        if (pool.isShutdown()) return;
        int expired = jobs.expireExhausted();
        if (expired > 0) counter(JobStore.State.FAILED).increment(expired);
        int available = slots.availablePermits();
        for (int index = 0; index < available && slots.tryAcquire(); index++) {
            pool.execute(() -> {
                try {
                    var work = jobs.claim();
                    if (work != null) processing.record(() -> process(work));
                } catch (Exception error) {
                    log.error("Analysis worker could not update durable state.", error);
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void process(JobStore.Work work) {
        var job = work.job();
        try {
            var update = registry.fetch(job.apiId(), job.newVersionId());
            var baseline = job.baselineVersionId() == null ? null : registry.fetch(job.apiId(), job.baselineVersionId());
            var result = Compatibility.compare(baseline, update);
            if (jobs.complete(work, result)) counter(JobStore.State.COMPLETED).increment();
        } catch (RegistryClient.Unavailable unavailable) {
            failure(work, unavailable.getMessage(), true);
        } catch (OpenApiDocument.Invalid invalid) {
            failure(work, invalid.code() + ": " + invalid.getMessage(), false);
        } catch (Exception unexpected) {
            log.warn("Analysis {} attempt {} did not complete.", job.id(), job.attemptCount(), unexpected);
            failure(work, "The analysis attempt failed unexpectedly.", true);
        }
    }

    private void failure(JobStore.Work work, String detail, boolean retryable) {
        var state = jobs.fail(work, detail.substring(0, Math.min(detail.length(), 1000)), retryable);
        if (state != null) counter(state).increment();
    }

    private Counter counter(JobStore.State state) {
        return metrics.counter("sentinel.analysis.jobs", "state", state.name());
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(20, TimeUnit.SECONDS)) pool.shutdownNow();
    }
}
