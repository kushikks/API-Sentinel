# Developer A resilience

Registry stores the version, analysis request and outbox event in one transaction. A broker or Analyzer outage does not prevent the transaction from committing. A relay locks the next due outbox row, publishes with a persistent message, waits up to three seconds for a confirmation, then marks it published. Unroutable or unconfirmed events remain durable and retry with capped backoff. RabbitMQ counters are separate from database backlog.

Analyzer consumes durable quorum messages with prefetch four and acknowledges after the ingest transaction. PostgreSQL deduplicates event IDs and comparison pairs. Invalid envelopes go to the dead-letter queue. Up to twenty broker deliveries guard against repeated consumer errors. Operators can replay recoverable records from the dead-letter queue after the underlying fault is resolved.

| Failure | Expected behavior |
| --- | --- |
| Analyzer unavailable | Registry stores versions and submissions. The queue retains the unconsumed events. |
| Registry unavailable | Existing Analyzer results and Identity remain available. Synchronous calls use connect and read deadlines and Resilience4j circuit breakers. |
| RabbitMQ unavailable | Registry and Analyzer retain pending outbox rows. HTTP health is based on each service's own serving capability and PostgreSQL connection. |
| Invalid contract or budget exceeded | Only the affected job enters `FAILED`. Its diagnostic remains available through REST. |
| Worker exits during an analysis | A replica can reclaim the expired lease after 90 seconds. Lease tokens fence stale workers from saving results. |
| Repeated delivery | A repeated event or submission pair returns one logical analysis. |

A job starts in `QUEUED`, claims a row with `SKIP LOCKED`, and moves through `RUNNING`. Transient failures use `RETRY_PENDING` with exponential delay and a maximum of eight attempts. A permanent input error or exhausted retry budget produces `FAILED`. A successful worker atomically commits `COMPLETED`, findings, the report and the completion outbox event. Failed jobs are retained for diagnosis. Operators can investigate broker dead letters separately from jobs rejected after durable ingestion.

Analyzer exports processing duration, counts by final or retry state, and sampled pending-job and pending-outbox counts at `/actuator/prometheus` with the metrics service key. Monitor can probe the private `/actuator/health` endpoints for outage detection. Developer B owns incident persistence, notifications and their acceptance evidence.

The Compose configuration refreshes Eureka client service lists and instance status every ten seconds and updates the Eureka response cache every five seconds. Registry and Analyzer use the Eureka client's discovery cache directly. Faster refresh limits the recovery delay when a previously unavailable instance comes back.

Use `node tests/developer-a/resilience.mjs` on the standalone Compose stack to reproduce the automated Analyzer, Registry and RabbitMQ outage scenarios. Two replicas on the same Docker engine demonstrate process scaling.

## Gemini request limits

Suggestions run separately from deterministic comparisons. Analyzer checks a shared PostgreSQL cooldown of 30 seconds per requesting account and spaces provider requests by at least two seconds across replicas. Each replica allows two provider calls in flight. When Gemini returns 429, Analyzer honors `Retry-After` up to one hour and applies that cooldown across replicas. If Gemini provides no usable retry duration, the cooldown is one minute.

The provider connection has a three-second connect timeout and a 12-second total deadline, including the response body. The body is limited to 64 KiB. Analyzer validates that output contains exactly one recommendation for each selected finding and bounded verification and modification lists. Failures return a structured problem and leave the deterministic findings and report available.
