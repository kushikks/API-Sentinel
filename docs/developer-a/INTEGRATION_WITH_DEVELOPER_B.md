# Developer B integration

## Gateway routes

| Prefix | Eureka destination |
| --- | --- |
| `/auth/**` | `lb://identity-service` |
| `/apis`, `/apis/**` | `lb://registry-service` |
| `/analyses`, `/analyses/**` | `lb://analyzer-service` |

Keep public paths and the refresh cookie path `/auth`. Preserve the CORS and CSRF header behavior for allowed browser origins. Configure the expected issuer and audience and the internal Identity JWKS URI, `http://identity:8081/auth/.well-known/jwks.json` on the default Compose network. The Gateway may cache JWKS and verify locally. Permit the public Identity routes listed in the API reference and enforce bearer authentication on protected routes. Put rate limits on authentication and upload endpoints. Route only the public business paths above. Actuator probes, Eureka, service credentials, and all `/internal/**` calls stay on the private network.

## Impact input

Bind the durable quorum queue `impact.analysis-completed.v1` to topic exchange `apisentinel.events` with routing key `analyzer.analysis.completed.v1`. Use the provided JSON Schema and idempotent inbox handling. When `baselineVersionId` is null, record or skip the initial version according to the Impact projection requirements. Aggregate finding counts alone cannot establish exposure.

```bash
curl -fsS "http://analyzer:8083/internal/analyses/$ANALYSIS_ID/findings?severity=BREAKING&page=0&size=100" \
  -H "X-Sentinel-Service-Key: $IMPACT_SERVICE_KEY"
curl -fsS "http://registry:8082/internal/apis/$API_ID" \
  -H "X-Sentinel-Service-Key: $IMPACT_SERVICE_KEY"
```

Continue paginating while `hasNext` is true. Store `apiId`, `analysisId`, `findingId` and correlation ID alongside endpoint or field exposure. Findings identify method, path, and contract JSON path. Distinguish endpoint exposure, field exposure and separately observed runtime failure. Impact owns the consumer mapping and `IMPACT_ASSESSED` publication. Alert owns notification workflows. Monitor probes `/actuator/health` on the private addresses.

## Shared deployment and frontend

Combine `infrastructure/developer-a.compose.yml` with Developer B's Compose configuration. The first Compose file sets the base path for relative mounts. Supply the shared RabbitMQ service, PostgreSQL host and credentials, Gateway, and other services there. Omit `infrastructure/discovery/standalone.compose.yml` when B supplies database and broker. For a shared network, the base fragment expects Compose DNS aliases `postgres`, `rabbitmq`, `discovery` and `identity`, with environment overrides for the database and broker addresses.

The Vite harness lives in the frontend root. Compose `SessionProvider` under the TanStack Query provider, then use the exported `SignIn`, `SignUp`, `ProtectedRoute`, `ApiCatalog`, `ApiDetail`, `AnalysisHistory` and `AnalysisDetail` screens in the shared React Router. The `developerARoutes` export demonstrates composition with an included workspace shell and fallback. Use the individual screens when integrating Developer B's sidebar and routing. The production browser build expects public API prefixes on the same origin by default. If using separate hosts, keep frontend and API on the same HTTPS site and allow the exact frontend origin so the browser can send the SameSite refresh cookie.

Use [testing and report inputs](TESTING.md) to populate Developer B's consolidated XLSX report. Use [event contracts](EVENT_CONTRACTS.md) for routing, schema and example payloads.
