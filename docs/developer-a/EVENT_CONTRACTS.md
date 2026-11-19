# Developer A event contracts

Publish JSON with content type `application/json`, a persistent delivery mode and the envelope's `eventId` as the AMQP message ID. The durable topic exchange is `apisentinel.events`.

| Event | Routing key | Durable quorum queue |
| --- | --- | --- |
| `VERSION_REGISTERED` | `registry.version.registered.v1` | `analyzer.version-registered.v1` |
| `ANALYSIS_COMPLETED` | `analyzer.analysis.completed.v1` | `impact.analysis-completed.v1` |

The versioned JSON Schemas live in `contracts/events/`. This is a version registration envelope.

```json
{
  "eventId": "7508ee94-bb13-419b-a43f-e432242bd15b",
  "schemaVersion": 1,
  "eventType": "VERSION_REGISTERED",
  "correlationId": "6b76250b-c7e3-48e1-a53d-2e719844549c",
  "entityId": "7cf419ce-eef7-4b7e-8b6a-3435f39ac5cc",
  "occurredAt": "2026-01-01T10:15:30Z",
  "producer": "registry-service",
  "payload": {
    "analysisId": "e0a6fdef-dd51-4bc4-ac37-3119e3f1ad74",
    "apiId": "24fc0190-e07d-40e5-9b0a-ccd1a721e2bb",
    "versionId": "7cf419ce-eef7-4b7e-8b6a-3435f39ac5cc",
    "baselineVersionId": "68828abd-1586-480e-a00d-01298dac1d12",
    "ownerId": "abc7bff8-f5f9-4878-bf48-8cb89ee868d4",
    "submittedBy": "abc7bff8-f5f9-4878-bf48-8cb89ee868d4"
  }
}
```

The completion envelope reuses the correlation and analysis IDs.

```json
{
  "eventId": "1f32d28d-04ab-4218-8b58-eeea08e17a53",
  "schemaVersion": 1,
  "eventType": "ANALYSIS_COMPLETED",
  "correlationId": "6b76250b-c7e3-48e1-a53d-2e719844549c",
  "entityId": "e0a6fdef-dd51-4bc4-ac37-3119e3f1ad74",
  "occurredAt": "2026-01-01T10:15:33Z",
  "producer": "analyzer-service",
  "payload": {
    "analysisId": "e0a6fdef-dd51-4bc4-ac37-3119e3f1ad74",
    "apiId": "24fc0190-e07d-40e5-9b0a-ccd1a721e2bb",
    "baselineVersionId": "68828abd-1586-480e-a00d-01298dac1d12",
    "newVersionId": "7cf419ce-eef7-4b7e-8b6a-3435f39ac5cc",
    "status": "COMPLETED",
    "breakingCount": 2,
    "potentiallyBreakingCount": 0,
    "nonBreakingCount": 2
  }
}
```

For the first version, both events carry `baselineVersionId: null`. A successful initial registration completes with zero findings. Impact can skip exposure calculation for that record. UUID identifiers are stable across retries, and timestamps are UTC ISO-8601. Event producers may add optional fields within v1. Required-field or semantic changes require a new schema version and routing key.

Analyzer acks after committing inbox and job updates. Repeated `eventId` values are idempotent. An incompatible payload or conflicting logical comparison identity is rejected. The Analyzer queue uses a delivery limit of 20 and dead-letters to `apisentinel.dead` using routing key `analyzer.version-registered.v1.dead`, bound to a quorum queue of the same name. Operators can inspect and replay corrected or recoverable messages using the original event ID. The Impact queue is declared even when Developer B's consumer is absent. Its consumer retry and dead-letter policy belongs to Developer B.

Outbox relays hold a row lock until a broker confirm, handle unroutable returns, and use capped backoff between retries. A process crash between broker confirmation and the database update can deliver duplicates. Consumers should persist an inbox ID before acknowledging and should tolerate repeated delivery of the same analysis ID.
