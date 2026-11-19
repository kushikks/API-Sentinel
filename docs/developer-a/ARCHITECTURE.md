# Developer A architecture

API Sentinel persists uploaded OpenAPI versions and compares them asynchronously. The Identity, Registry, and Analyzer are independent Spring Boot processes. They register as `identity-service`, `registry-service`, and `analyzer-service` in Eureka.

| Process | Responsibility | Data owned |
| --- | --- | --- |
| Identity | Registration, credentials, signed access tokens, refresh sessions and audit | `identity_db` |
| Registry | API metadata, immutable version artifacts and analysis submissions | `registry_db` |
| Analyzer | Durable analysis jobs, contract compatibility findings and downloadable JSON reports | `analyzer_db` |
| Discovery | Eureka instance registration and discovery | In-memory registry |

1. Identity issues an RS256 access token and sets an HttpOnly refresh cookie. Registry and Analyzer verify the signature, issuer, audience and expiry locally, with keys cached from JWKS.
2. Registry validates and stores exact specification bytes. An analysis submission and its outbox event commit in one PostgreSQL transaction.
3. RabbitMQ routes the version event to Analyzer's durable quorum queue. Analyzer commits the inbox event and logical job before acknowledging delivery.
4. Analyzer leases the job from PostgreSQL. It resolves Registry through Eureka, retrieves the immutable version artifacts, and compares the OpenAPI structure. It commits findings, a report and a completion event together.
5. The Analyzer completion queue retains events for Impact. Impact can retrieve findings and Registry metadata using its scoped service credential.

Spring Boot 3.5.15 and Spring Cloud 2025.0.3 are a matched release pair. The build targets Java 21. The services use Spring JDBC with explicit parameterized SQL for constraints, transactions and `FOR UPDATE SKIP LOCKED`. Boot 3.5 uses the final open-source Spring Cloud 2025.0 train. [Spring Cloud release notes](https://spring.io/blog/2026/06/11/spring-cloud-2025-0-3-aka-northfields-has-been-released/)

JWTs, service keys, or events carry UUIDs across boundaries. Services use their own database credentials. The browser uses the same-origin Gateway path in deployment and an equivalent Vite proxy in local development.
