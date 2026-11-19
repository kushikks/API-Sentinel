# Developer A setup

From the repository root, use Docker with the Compose plugin, JDK 21 for local commands, Maven 3.9.x, and Node 22. The container build runs on Java 21. Ports 8761, 8081, 8082 and 5173 must be available on the local host. Analyzer gets an ephemeral host port for every replica.

## Windows PowerShell

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/developer-a/init-local.ps1
$compose = @('compose','--env-file','.local/.env','-f','infrastructure/developer-a.compose.yml','-f','infrastructure/discovery/standalone.compose.yml')
docker @compose config --quiet
docker @compose up -d --build --wait
$env:ANALYZER_ORIGIN = "http://$(docker @compose port analyzer 8083)"
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run dev
```

Open [API Sentinel](http://127.0.0.1:5173) and create an API owner account. The [Eureka dashboard](http://127.0.0.1:8761) lists services once their first registration and heartbeat are visible. The demo contract fixtures are in `tests/developer-a/fixtures/`. Keep the frontend origin consistent during sign-in because browser cookies are host-scoped.

## Bash

```bash
bash tests/developer-a/init-local.sh
compose=(docker compose --env-file .local/.env -f infrastructure/developer-a.compose.yml -f infrastructure/discovery/standalone.compose.yml)
"${compose[@]}" up -d --build --wait
export ANALYZER_ORIGIN="http://$("${compose[@]}" port analyzer 8083)"
npm --prefix frontend ci
npm --prefix frontend run dev
```

The initialization script generates unique local PostgreSQL, RabbitMQ and service credentials and an RSA PKCS12 file under ignored `.local/`. The development cookie is configured for local HTTP only. PostgreSQL and RabbitMQ load persistent state from named volumes. Preserve the RabbitMQ node hostname when reusing an existing broker volume. Run `docker @compose stop` in PowerShell to stop the stack with its data intact.

## Configuration

| Variable | Use |
| --- | --- |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_KEY_ID` | Public issuer identifier, expected audience, and signing key identifier |
| `JWT_KEYSTORE_PATH`, `JWT_KEYSTORE_PASSWORD` | Read-only PKCS12 file with the RSA alias `sentinel` and its password |
| `IDENTITY_DB_URL`, `REGISTRY_DB_URL`, `ANALYZER_DB_URL` | JDBC URLs including each service's `currentSchema` |
| `{IDENTITY,REGISTRY,ANALYZER}_DB_USER`, `{IDENTITY,REGISTRY,ANALYZER}_DB_PASSWORD` | Service-owned database credentials |
| `POSTGRES_PASSWORD` | Local PostgreSQL administrator credential for the standalone fragment |
| `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST` | Shared broker coordinates |
| `ANALYZER_REGISTRY_KEY`, `REGISTRY_ANALYZER_KEY`, `IMPACT_SERVICE_KEY`, `METRICS_SERVICE_KEY` | Scoped random credentials with at least 32 characters |
| `SECURE_COOKIE`, `CORS_ORIGINS` | Secure refresh cookie, with a comma-separated cross-origin allowlist when needed |
| `ANALYZER_CONCURRENCY` | Job threads per Analyzer replica, from 1 to 8 with 2 by default |
| `GEMINI_API_KEY` | Optional Gemini API key passed only to Analyzer. Leave unset to disable AI generation |
| `GEMINI_MODEL` | Gemini text model with structured output support. Defaults to `gemini-3.5-flash-lite` |
| `IDENTITY_ORIGIN`, `REGISTRY_ORIGIN`, `ANALYZER_ORIGIN` | Vite development proxy targets |
| `VITE_API_BASE` | Optional browser-facing API origin set at frontend build time |

A standalone service JVM uses `DB_URL`, `DB_USER`, `DB_PASSWORD`, `EUREKA_URL`, `JWT_ISSUER`, and `JWT_JWKS_URL` where applicable. Identity's JVM uses `JWT_KEYSTORE` as a Spring resource location. The base Compose fragment supplies these service-specific values. See [Developer B integration](INTEGRATION_WITH_DEVELOPER_B.md) for combining the base fragment with shared infrastructure.

## Enable Gemini migration suggestions

Supply `GEMINI_API_KEY` in the Analyzer's server environment or through the ignored local `.local/.env` file. Use the deployment secret store for hosted environments. Recreate only the Analyzer service after changing the configuration. A key and a working model entitlement are needed for a live call. Without them, the UI shows that generation is unavailable and still displays the saved findings.

```powershell
docker @compose up -d --build --no-deps --wait analyzer
```

The standalone Analyzer host port is assigned dynamically. If it changes on recreation, restart Vite with `ANALYZER_ORIGIN` set to the new port as shown in setup. For a manual check, open an owned completed analysis with breaking findings and select **Generate suggestions**.

Analyzer uses Google's GenerateContent REST API with structured output. The default Flash-Lite model supports structured text responses. [GenerateContent reference](https://aistudio.google.com/api-reference/generate-content) · [Flash-Lite model documentation](https://aistudio.google.com/docs/models/gemini-3.5-flash-lite)
