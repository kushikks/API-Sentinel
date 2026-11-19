# Developer A implementation status

API Sentinel's Developer A stack implements accounts, secure sessions, API registration, immutable OpenAPI versioning, durable contract analysis, and optional Gemini migration suggestions. The local Docker stack passed a real Student API workflow with two removed response fields and verified that both findings remained available when Gemini was unconfigured. Full platform release acceptance requires integration and evidence from Developer B and the public deployment.

| Workstream | Status | Evidence or dependency |
| --- | --- | --- |
| Identity, JWT verification and profile | IMPLEMENTED_AND_TESTED | Compose authentication smoke and signed-token validation unit test |
| Registry and immutable versions | IMPLEMENTED_AND_TESTED | Real JSON and YAML upload, exact bytes, duplicate rejection and submission in Compose |
| Analyzer and report | IMPLEMENTED_AND_TESTED | Real event delivery and Registry fetch, baseline handling, two breaking findings, stored report |
| Eureka | IMPLEMENTED_AND_TESTED | All three business services appeared as UP |
| Frontend | IMPLEMENTED_AND_TESTED | TypeScript/Vite build and all three Playwright workflows on installed Edge, including mocked Gemini success, rate limiting and fallback |
| Developer A integrations | IMPLEMENTED_AND_TESTED | Full Maven verification passed all 18 Surefire and 11 Failsafe tests |
| Outage recovery | IMPLEMENTED_AND_TESTED | Analyzer, RabbitMQ, and Registry stop/restart scenarios passed |
| Local load acceptance | IMPLEMENTED_AND_TESTED | 10,000 reads, 6,000 sustained reads and both 100-job runs passed. The two replicas completed 47 and 53 jobs |
| Gemini suggestions | IMPLEMENTED_AND_TESTED | Mocked provider coverage and real no-key fallback passed. Live Gemini returned two recommendations for the Student API fixture and both rendered in Edge. |
| Developer B platform integration | BLOCKED | Gateway, Impact, Monitor, Alert, deployment and XLSX pipeline are external to this checkout |

## R01–R15 ownership and acceptance

| Requirement | Developer A delivers | Developer B or launch evidence |
| --- | --- | --- |
| R01 Independent services | Three business containers with private Actuator health endpoints and Eureka | Combined run with Impact, Monitor and Alert |
| R02 Public HTTPS | Encrypted-cookie and public-origin configuration, documented ingress routes | Public DNS, HTTPS ingress and live URL |
| R03 Request and worker load | Authenticated k6 reads, 100 comparative jobs, worker scaling and local recovery scenario | Rerun integrated full system at declared thresholds and hardware |
| R04 Persistent databases | Separate PostgreSQL roles/schemas and Flyway migrations for A services | Persistence for B services and combined test |
| R05 Cross-service data | Scoped REST interfaces and version/completion events | Impact projection from actual consumer records |
| R06 Communication | Eureka REST lookups and RabbitMQ workflow | Gateway routing and A-to-B event consumption |
| R07 Resilience | Outboxes, bounded job retries, circuit breakers, leases and dead-letter routing | Exercise downstream failure and full recovery |
| R08 Eureka | Discovery server and all A service clients | Combined service registration and removal demo |
| R09 Gateway | Public and internal route contract, same-origin frontend support | Implement and verify Spring Cloud Gateway |
| R10 Outage detection | Private health probes and job metrics | Monitor polling and durable incident detection |
| R11 Outage alerting | Observable service health and integration contracts | Alert persistence and automatic dashboard notification |
| R12 Authentication | Registration, login, JWKS, profile, refresh and logout | Route protected paths and verify B services accept the same JWT contract |
| R13 Security | RBAC, read authorization, secure tokens, safe upload parsing, scoped internal access | HTTPS, edge limits, downstream RBAC and Monitor SSRF protection |
| R14 Automated tests | Surefire, Failsafe, Compose smoke and Playwright | Downstream and integrated automated suites |
| R15 Excel test report | Genuine XML/JSON artifacts and sheet mapping | Consolidated XLSX generator, authenticated download and published evidence |

## Reproduction

Use [Setup](SETUP.md) for Compose and Vite, [Testing](TESTING.md) for build and report commands, and [Load testing](LOAD_TESTING.md) for the local benchmark. The Maven reactor targets Java 21 and the Docker images run Java 21. The Windows host build used the installed JDK 24 with compiler release 21.

The full `mvn -B -ntp -Dmaven.repo.local=.local/m2 -o -T 2 verify` run passed all 29 Java tests with zero failures, errors or skips. `npm.cmd --prefix frontend run build` succeeded, and `npm.cmd --prefix frontend run test:e2e` passed all three Playwright workflows. Eureka reported one Identity, one Registry and two Analyzer instances UP. The Docker smoke confirmed that the suggestions endpoint returned HTTP 503 with `GEMINI_NOT_CONFIGURED` and the saved report still returned both original findings. Raw logs and JSON are under `.local/evidence/`, and the Maven XML is under each module's `target` directory.

On 19 November 2026, a separate Edge run used the two local Student API fixtures and the configured Gemini provider. The suggestions endpoint returned HTTP 200 in 8.48 seconds with two grounded recommendations from `gemini-3.5-flash-lite`, one for each removed response field. Edge displayed both as unverified recommendations. The request from the browser contained no Gemini key. Evidence is in `.local/evidence/gemini-live.json` and `.local/evidence/gemini-live.png`.

## Implementation files

| Area | Paths |
| --- | --- |
| Identity | `services/identity/` for auth endpoints, RSA signing, BCrypt, refresh families, migrations, tests and image |
| Registry | `services/registry/` for API ownership, immutable versions, scoped REST, submission outbox, migrations, tests and image |
| Analyzer | `services/analyzer/` for broker ingestion, job leases and retries, findings, reports, optional migration suggestions, metrics, migrations, tests and image |
| Comparison and integration | `contracts/openapi/` for upload validation, comparison rules and public/internal OpenAPI specifications. `contracts/events/` for envelopes, routing, outbox, security utilities and JSON Schemas |
| Infrastructure | `infrastructure/developer-a.compose.yml` and `infrastructure/discovery/` for Eureka and the standalone PostgreSQL and RabbitMQ setup |
| Frontend | `frontend/src/features/{auth,apis,analysis}/` for exported routes, session handling, registry UI and analysis UI. The frontend root includes the Vite harness and dependency lock |
| Fixtures and evidence tools | `tests/developer-a/` and `tests/load/analyzer/` for local setup, real workflow verification, fault injection and k6 |

The working tree started empty on the unborn `master` branch. The implementation stayed in Developer A's areas and the shared build and frontend files required to run the stack.

## Outage evidence

| Dependency stopped | Verified behavior | Job completed after restart |
| --- | --- | --- |
| Analyzer | Registry persisted the version, Identity served the profile, and RabbitMQ retained one queued event | 15.45 s |
| RabbitMQ | Registry retained one unpublished outbox row, Identity served the profile, and Analyzer served a prior report | 17.96 s |
| Registry | Analyzer entered retry, Identity and prior reports stayed available, and the saved specification survived restart byte-for-byte | 29.32 s |

The Registry scenario initially exceeded 90 seconds because Eureka and the client load balancer took time to refresh stale service data. The reconfigured stack passed all three scenarios. The measured report is `.local/evidence/resilience.json`.

The [local load results](LOAD_TESTING.md#measured-results) record latency, throughput, raw request counts and the worker distribution. The local stack runs two Analyzer replicas alongside Identity, Registry, Eureka, PostgreSQL and RabbitMQ. Public HTTPS and Developer B's downstream flows require a separate integrated run.

The comparison engine uses Swagger Parser for validation and application-owned compatibility rules over the parsed contract. It covers the documented operation, parameter, media, response, property and schema rules, including local references and nested arrays. JSON Schema composition, content negotiation and some constraints receive conservative review findings. A compatibility finding indicates a contract-level change. Consumer exposure and runtime incident evidence come from separate services.
