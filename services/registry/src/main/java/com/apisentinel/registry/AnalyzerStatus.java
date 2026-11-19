package com.apisentinel.registry;

import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
class AnalyzerStatus {
    private final RestClient http;
    private final CircuitBreaker breaker;
    private final String key;

    AnalyzerStatus(@LoadBalanced RestClient.Builder http, CircuitBreakerFactory<?, ?> circuits,
                   @Value("${sentinel.registry-analyzer-key}") String key) {
        this.http = http.clone().baseUrl("http://analyzer-service").build();
        this.breaker = circuits.create("analyzer-status");
        this.key = key;
    }

    Latest latest(Catalog.Submission submission) {
        return breaker.run(() -> {
            try {
                var status = http.get().uri("/internal/analyses/{id}/status", submission.analysisId())
                        .header("X-Sentinel-Service-Key", key).retrieve().body(Status.class);
                return new Latest(submission, true, Objects.requireNonNull(status));
            } catch (HttpClientErrorException.NotFound queued) {
                return new Latest(submission, true, null);
            }
        }, unavailable -> new Latest(submission, false, null));
    }

    record Status(String status, int attemptCount, Instant startedAt, Instant completedAt, String error) {}
    record Latest(Catalog.Submission submission, boolean analyzerAvailable, Status job) {}
}
