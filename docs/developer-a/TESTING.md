# Developer A testing and report inputs

Use Docker running locally and execute from the repository root.

```bash
mvn -B -ntp test
mvn -B -ntp verify
npm --prefix frontend ci
npm --prefix frontend run build
```

Surefire runs parser, comparison and token validation tests. Failsafe runs Identity HTTP security and persistence, Registry ownership and recovery, and Analyzer delivery, idempotency, leasing and reporting checks. Testcontainers starts real PostgreSQL and RabbitMQ. Analyzer's client tests fetch two fixtures from a listening HTTP server. The Compose smoke test exercises the real Registry and Analyzer against Eureka and the broker.

```powershell
$compose = @('compose','--env-file','.local/.env','-f','infrastructure/developer-a.compose.yml','-f','infrastructure/discovery/standalone.compose.yml')
$env:ANALYZER_ORIGIN = "http://$(docker @compose port analyzer 8083)"
node tests/developer-a/smoke.mjs
npm.cmd --prefix frontend run test:e2e
node tests/developer-a/resilience.mjs
```

The browser suite defaults to installed Microsoft Edge. Set `SENTINEL_BROWSER=chrome` to use installed Chrome. To include Impact authorization in the smoke test, set `IMPACT_SERVICE_KEY` from the generated local environment before starting Node. Keep secrets out of command or result logs. The resilience script stops and restarts services in the local `sentinel-a` project. Run it independently of throughput tests.

## Machine-readable inputs for Developer B

| Evidence | Path | Excel mapping |
| --- | --- | --- |
| Unit XML | `contracts/openapi/target/surefire-reports/TEST-*.xml`, `services/{identity,analyzer}/target/surefire-reports/TEST-*.xml` | Unit Tests and Security Tests |
| Integration XML | `services/{identity,registry,analyzer}/target/failsafe-reports/TEST-*.xml` | Integration Tests and Security Tests |
| Browser XML | `.local/evidence/playwright.xml` | Integration Tests |
| Compose smoke JSON | `.local/evidence/smoke.json` | Integration Tests |
| Outage JSON | `.local/evidence/resilience.json` | Resilience Tests |
| k6 and queue JSON | `.local/evidence/k6-*.json`, `.local/evidence/queue-*.json` | Load Tests |

For each JUnit testcase, map `classname` and `name` to test class and method, and use `time` as seconds. Derive Passed, Failed, Error, or Skipped from the testcase child elements. Preserve the actual failure message. For the Summary sheet, record suite counts, timestamp, code revision or workspace state, test environment, and aggregate status. For k6, read `metrics.http_reqs.values.count`, `rate`, `metrics.http_req_duration.values`, `metrics.http_req_failed.values.rate`, and the threshold `ok` flags. Copy outage results only after a run produces them. The consolidated XLSX pipeline and its download endpoint belong to Developer B.

## Migration suggestions

Maven verification covers the actual Gemini adapter using a loopback HTTP fixture and tests the authenticated endpoint against findings persisted by the deterministic Analyzer. It verifies bounded response bodies and timeouts, rejects malformed or ungrounded responses, and checks that provider quota limits apply across callers. Playwright uses explicit fixture responses for rate limiting, failure and successful guidance while running against the normal application for the other workflows.

With Gemini disabled, set `EXPECT_GEMINI_UNCONFIGURED=1` before running `node tests/developer-a/smoke.mjs` against the rebuilt Compose stack. The smoke test verifies that the generation endpoint returns a structured 503 and that the deterministic report is identical afterwards. This smoke test and the provider fixtures do not make live Google calls.
