import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { setTimeout } from 'node:timers/promises'

const origins = {
  identity: process.env.IDENTITY_ORIGIN ?? 'http://127.0.0.1:8081',
  registry: process.env.REGISTRY_ORIGIN ?? 'http://127.0.0.1:8082',
  analyzer: process.env.ANALYZER_ORIGIN ?? 'http://127.0.0.1:8083',
  discovery: process.env.DISCOVERY_ORIGIN ?? 'http://127.0.0.1:8761',
}
const ids = randomUUID()
const email = 'smoke-' + ids + '@example.test'
const password = randomUUID()
const auth = { 'X-Sentinel-CSRF': '1' }
const json = (data, headers = {}) => ({ headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(data) })

async function call(origin, path, status, options = {}) {
  const response = await fetch(origins[origin] + path, { ...options, signal: AbortSignal.timeout(8000) })
  assert.equal(response.status, status, path + ' ' + (response.ok ? '' : await response.clone().text()))
  return response
}

for (const name of Object.keys(origins)) {
  const health = await call(name, '/actuator/health', 200)
  assert.equal((await health.json()).status, 'UP')
}
const expectedServices = ['IDENTITY-SERVICE', 'REGISTRY-SERVICE', 'ANALYZER-SERVICE']
let instances = []
for (let attempt = 0; attempt < 20; attempt++) {
  const discovery = await call('discovery', '/eureka/apps', 200, { headers: { Accept: 'application/json' } })
  instances = (await discovery.json()).applications.application.flatMap(service =>
    service.instance.filter(instance => instance.status === 'UP').map(() => service.name))
  if (expectedServices.every(name => instances.includes(name))) break
  await setTimeout(1000)
}
for (const name of expectedServices) {
  assert(instances.includes(name), name + ' is missing from Eureka')
}
await call('identity', '/auth/register', 403, { method: 'POST', ...json({ email, password, displayName: 'Smoke Test', role: 'ADMIN' }) })
const registered = await call('identity', '/auth/register', 201, { method: 'POST',
  ...json({ email, password, displayName: 'Smoke Test', role: 'API_OWNER' }) })
const owner = await registered.json()
await call('identity', '/auth/register', 409, { method: 'POST', ...json({ email, password, displayName: 'Duplicate', role: 'API_OWNER' }) })
await call('identity', '/auth/login', 401, { method: 'POST', ...json({ email, password: randomUUID() }, auth) })
const login = await call('identity', '/auth/login', 200, { method: 'POST', ...json({ email, password }, auth) })
let cookie = login.headers.get('set-cookie').split(';')[0]
let token = (await login.json()).accessToken
const headers = () => ({ Authorization: 'Bearer ' + token })
await call('identity', '/auth/me', 401)
const profile = await call('identity', '/auth/me', 200, { headers: headers() })
assert.equal((await profile.json()).id, owner.id)
const rotated = await call('identity', '/auth/refresh', 200, { method: 'POST', headers: { ...auth, Cookie: cookie } })
const previous = cookie
cookie = rotated.headers.get('set-cookie').split(';')[0]
assert.notEqual(previous, cookie)
token = (await rotated.json()).accessToken
const api = await (await call('registry', '/apis', 201, { method: 'POST',
  ...json({ name: 'Student Smoke ' + ids, description: 'Integration fixture' }, headers()) })).json()
const root = '/apis/' + api.id + '/versions'
const upload = async (label, file, mime, bytes, expected = 201) => {
  const form = new FormData()
  form.set('versionLabel', label)
  form.set('file', new Blob([bytes], { type: mime }), file)
  const result = await call('registry', root, expected, { method: 'POST', headers: headers(), body: form })
  return result.json()
}
const baselineBytes = await readFile(new URL('./fixtures/student-v1.yaml', import.meta.url))
const newBytes = await readFile(new URL('./fixtures/student-v2.json', import.meta.url))
await upload('unsafe', 'unsafe.yaml', 'application/yaml', baselineBytes.toString().replace(
  "'#/components/schemas/Student'", "'https://example.invalid/shared.yaml'"), 422)
const first = await upload('1.0.0', 'openapi.yaml', 'application/yaml', baselineBytes)
const last = await upload('2.0.0', 'openapi.json', 'application/json', newBytes)
assert.equal(last.analysis.baselineVersionId, first.version.id)
await upload('2.0.0', 'openapi.json', 'application/json', newBytes, 409)
const versionHistory = await call('registry', root, 200, { headers: headers() })
assert.deepEqual((await versionHistory.json()).items.map(item => item.versionLabel), ['2.0.0', '1.0.0'])
const spec = await call('registry', root + '/' + first.version.id + '/spec', 200, { headers: headers() })
assert.deepEqual(Buffer.from(await spec.arrayBuffer()), baselineBytes)
const duplicate = await call('registry', root + '/' + last.version.id + '/analyze', 202,
  { method: 'POST', ...json({ baselineVersionId: first.version.id }, headers()) })
assert.equal((await duplicate.json()).analysisId, last.analysis.analysisId)

async function completed(id) {
  for (let index = 0; index < 80; index++) {
    const result = await fetch(origins.analyzer + '/analyses/' + id, { signal: AbortSignal.timeout(7000), headers: headers() })
    if (result.status === 200) {
      const job = await result.json()
      if (job.status === 'COMPLETED') return job
      assert.notEqual(job.status, 'FAILED', JSON.stringify(job))
    } else assert.equal(result.status, 404, await result.text())
    await setTimeout(750)
  }
  throw new Error('Job did not complete within the test window')
}
const firstJob = await completed(first.analysis.analysisId)
assert.equal(firstJob.outcome, 'NO_BASELINE')
const job = await completed(last.analysis.analysisId)
assert.equal(job.breakingCount, 2)
const report = await (await call('analyzer', '/analyses/' + job.id + '/report', 200, { headers: headers() })).json()
assert.equal(report.schemaVersion, 1)
assert.deepEqual(report.findings.filter(finding => finding.severity === 'BREAKING').map(finding => finding.oldValue).sort(), ['integer', 'string'])
assert.deepEqual(report.findings.filter(finding => finding.severity === 'BREAKING').map(finding => finding.category),
  ['RESPONSE_PROPERTY_REMOVED', 'RESPONSE_PROPERTY_REMOVED'])
assert(report.findings.some(finding => finding.jsonPath.includes("['studentId']")))
assert(report.findings.some(finding => finding.jsonPath.includes("['name']")))
const latest = await call('registry', root + '/' + last.version.id + '/analysis', 200, { headers: headers() })
assert.equal((await latest.json()).job.status, 'COMPLETED')
const expectGeminiDisabled = process.env.EXPECT_GEMINI_UNCONFIGURED === '1'
if (expectGeminiDisabled) {
  const suggested = await call('analyzer', '/analyses/' + job.id + '/migration-suggestions', 503,
    { method: 'POST', headers: headers() })
  assert.equal((await suggested.json()).code, 'GEMINI_NOT_CONFIGURED')
  const unchanged = await call('analyzer', '/analyses/' + job.id + '/report', 200, { headers: headers() })
  assert.deepEqual(await unchanged.json(), report)
}
const key = process.env.IMPACT_SERVICE_KEY
if (key) {
  const internal = { 'X-Sentinel-Service-Key': key }
  const impacts = await call('analyzer', '/internal/analyses/' + job.id + '/findings?severity=BREAKING', 200, { headers: internal })
  assert.equal((await impacts.json()).items.length, 2)
  await call('registry', '/internal/apis/' + api.id, 200, { headers: internal })
  await call('registry', root.replace('/apis/', '/internal/apis/') + '/' + first.version.id + '/spec', 401, { headers: internal })
}
await call('identity', '/auth/logout', 204, { method: 'POST', headers: { ...auth, Cookie: cookie } })
await call('identity', '/auth/refresh', 401, { method: 'POST', headers: { ...auth, Cookie: cookie } })

const evidence = { timestamp: new Date().toISOString(), scenario: 'student-api-smoke', status: 'PASSED', healthy: Object.keys(origins),
  registered: instances, apiId: api.id, analysisId: job.id, findings: job.breakingCount, impactAccessVerified: Boolean(key),
  ...(expectGeminiDisabled ? { geminiDisabledFallbackVerified: true } : {}) }
await mkdir(new URL('../../.local/evidence/', import.meta.url), { recursive: true })
await writeFile(new URL('../../.local/evidence/smoke.json', import.meta.url), JSON.stringify(evidence, null, 2))
console.log(JSON.stringify(evidence))
