package com.apisentinel.shared;

import java.time.Instant;
import java.util.UUID;

public final class Events {
    public static final String EXCHANGE = "apisentinel.events";
    public static final String DEAD_EXCHANGE = "apisentinel.dead";
    public static final String REGISTERED_ROUTE = "registry.version.registered.v1";
    public static final String COMPLETED_ROUTE = "analyzer.analysis.completed.v1";
    public static final String ANALYZER_QUEUE = "analyzer.version-registered.v1";
    public static final String IMPACT_QUEUE = "impact.analysis-completed.v1";

    private Events() {}

    public record Envelope<T>(UUID eventId, int schemaVersion, String eventType, UUID correlationId,
                              UUID entityId, Instant occurredAt, String producer, T payload) {}

    public record VersionRegistered(UUID analysisId, UUID apiId, UUID versionId, UUID baselineVersionId,
                                    UUID ownerId, UUID submittedBy) {}

    public record AnalysisCompleted(UUID analysisId, UUID apiId, UUID baselineVersionId, UUID newVersionId,
                                    String status, int breakingCount, int potentiallyBreakingCount, int nonBreakingCount) {}
}
