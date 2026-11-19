# Developer A API reference

The versioned OpenAPI contracts are in `contracts/openapi/identity.v1.json`, `registry.v1.json` and `analyzer.v1.json`. Business routes use an RS256 bearer token. Role and owner checks are enforced at each service. Errors use `application/problem+json` with `status`, `detail` and `code`. Invalid form input may include a `fields` array. Lists use zero-based `page` and a `size` from 1 to 100 and return `items`, `page`, `size` and `hasNext`.

| Method and path | Result |
| --- | --- |
| `POST /auth/register` | 201 and the account profile |
| `POST /auth/login` | 200, access token and a refresh cookie |
| `POST /auth/refresh` | 200, a fresh access token and rotated cookie |
| `POST /auth/logout` | 204 and a cleared refresh cookie |
| `GET /auth/me` | 200 and the authenticated profile |
| `GET /auth/.well-known/jwks.json` | 200 and public signing keys |
| `POST /apis`, `GET /apis?q=students` | 201 registration or 200 paged search |
| `GET /apis/{apiId}`, `PATCH /apis/{apiId}` | 200 API metadata or update. Omitting a field preserves its value. Empty description or base URL clears it |
| `POST /apis/{apiId}/versions` | 201 and immutable version metadata, plus the analysis submission if requested |
| `GET /apis/{apiId}/versions`, `GET /apis/{apiId}/versions/{versionId}` | 200 version history or a single version |
| `GET /apis/{apiId}/versions/{versionId}/spec` | 200 exact uploaded JSON or YAML with an attachment header |
| `POST /apis/{apiId}/versions/{versionId}/analyze` | 202 and a stable analysis ID with a `Location` header |
| `GET /apis/{apiId}/versions/{versionId}/analysis` | 200 latest submission and observed Analyzer state when available |
| `GET /analyses` | 200 paged history with optional `apiId` and `status` filters |
| `GET /analyses/{analysisId}` | 200 persisted job |
| `GET /analyses/{analysisId}/findings` | 200 paged findings with optional exact `category` and `severity` filters |
| `GET /analyses/{analysisId}/summary` | 200 job state, outcome and counts |
| `GET /analyses/{analysisId}/report` | 200 versioned JSON attachment after completion, or 409 while unavailable |
| `POST /analyses/{analysisId}/migration-suggestions` | 200 unverified Gemini guidance for up to five saved findings |

Use `X-Sentinel-CSRF: 1` for login, refresh and logout. Invalid or expired credentials return 401. Role violations return 403 and unauthorized access to an owner-scoped resource returns 404. Duplicate API names per owner and duplicate version labels per API return 409. An upload above 1 MiB returns 413. An invalid or unsafe contract returns 422.

## Example workflow

Set `BASE` to the Gateway origin, or use the Vite proxy at `http://127.0.0.1:5173`.

```bash
curl -fsS "$BASE/auth/register" -H 'Content-Type: application/json' \
  --data '{"email":"demo@example.test","password":"change-this-local-demo-value","displayName":"API Demo","role":"API_OWNER"}'
curl -fsS "$BASE/auth/login" -H 'Content-Type: application/json' -H 'X-Sentinel-CSRF: 1' \
  --data '{"email":"demo@example.test","password":"change-this-local-demo-value"}'
curl -fsS "$BASE/apis" -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data '{"name":"Student API","description":"Student record contract"}'
curl -fsS "$BASE/apis/$API_ID/versions" -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F 'versionLabel=v1' -F 'analyze=false' -F 'file=@tests/developer-a/fixtures/student-v1.yaml'
curl -fsS "$BASE/apis/$API_ID/versions" -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F 'versionLabel=v2' -F 'file=@tests/developer-a/fixtures/student-v2.json'
curl -fsS "$BASE/apis/$API_ID/versions/$VERSION_ID/analyze" -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' --data "{\"baselineVersionId\":\"$BASELINE_ID\"}"
curl -fsS "$BASE/analyses/$ANALYSIS_ID/findings?severity=BREAKING" -H "Authorization: Bearer $ACCESS_TOKEN"
curl -fsS "$BASE/analyses/$ANALYSIS_ID/report" -H "Authorization: Bearer $ACCESS_TOKEN" -o compatibility.json
```

Upload defaults to `analyze=true`. Without an explicit baseline, Registry uses the immediately preceding immutable version in upload order. A first version completes with `NO_BASELINE` and zero findings. An explicit baseline can be any different version of the same API, so comparisons can run in either direction. Resubmitting the same comparison pair returns its existing analysis ID. A job can be briefly absent from Analyzer while delivery is pending. Registry's latest-submission endpoint still returns the durable submission and distinguishes an unavailable Analyzer from a job it has yet to receive.

## AI migration suggestions

The endpoint accepts no request data beyond the analysis ID. It requires a bearer token for the API owner or an administrator and an analysis with a completed comparison and eligible findings. It selects up to five saved findings, starting with breaking findings and then filling remaining slots with potentially breaking findings.

```bash
curl -fsS -X POST "$BASE/analyses/$ANALYSIS_ID/migration-suggestions" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

A successful response contains `analysisId`, `source: "GEMINI"`, `model`, `verified: false`, `eligibleFindingCount`, and `suggestions`. Each suggestion includes the stored finding ID, severity, category, method, route and JSON path when present, plus an explanation, potentially affected client code, recommended changes, and verification steps. These recommendations need review against the actual client code. The request does not change the analysis, report, or emitted events.

| Status | Meaning |
| --- | --- |
| 401 or 403 | Authentication failed, or the caller lacks the required role |
| 404 | The analysis is absent or belongs to a different owner |
| 409 | The analysis is incomplete |
| 422 | There are no breaking or potentially breaking findings |
| 429 | Request or provider limit reached. Read `Retry-After` or `retryAfterSeconds` from the problem response |
| 502 | The provider response was incomplete or failed structured output validation |
| 503 | Gemini is unavailable or its configuration is missing |
| 504 | The provider call timed out |
