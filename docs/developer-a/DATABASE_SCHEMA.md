# Developer A database schema

The standalone Compose setup creates three PostgreSQL schemas. Each application connects with a distinct role that owns only its schema. Local initialization revokes general schema and database access and grants only connection to the three application roles. Developer B can provision its own roles and schemas with corresponding grants. Flyway stores separate migration histories.

| Migration | Relations |
| --- | --- |
| Identity `V1__identity.sql` | `users`, `roles`, `user_roles`, `refresh_sessions`, `refresh_tokens`, `authentication_audit` |
| Registry `V1__registry.sql` | `apis`, `api_versions`, `specification_artifacts`, `analysis_requests` |
| Registry `V2__registry_outbox.sql` | `outbox_events` |
| Analyzer `V1__analyzer.sql` | `analysis_jobs`, `analysis_findings`, `analysis_artifacts`, `processed_events` |
| Analyzer `V2__analyzer_outbox.sql` | `outbox_events` |
| Analyzer `V3__migration_suggestion_limits.sql` | `migration_suggestion_limits` |

Identity normalizes account email to lowercase for uniqueness and uses a refresh family row lock to synchronize rotation and revocation. Refresh digests and password hashes are persisted. Audit stores an action, optional user ID and timestamp.

Registry uses case-insensitive names per owner and case-insensitive version labels per API. It stores a SHA-256 digest and exact bytes for each immutable upload. A PostgreSQL sequence orders versions. Submissions constrain both version IDs to the same API and enforce uniqueness for the baseline/new pair, including a null first baseline.

Analyzer uses the same natural comparison identity. Job indexes support owner and API history, due jobs and expired leases. Findings have a stable ordinal within a report, with a separate index for the severity and category filters. Reports are serialized once in the completion transaction. The Registry and Analyzer outboxes store routing keys, bytes, attempt counts, and confirmed publication timestamps. Dates are stored with timezone and serialized as ISO-8601.

The migration suggestion limiter stores the next permitted request time for each caller and the shared Gemini provider scope. Analyzer reserves both limits in a single transaction so replicas share a consistent cooldown. Prompts and generated recommendations are kept out of the database.
