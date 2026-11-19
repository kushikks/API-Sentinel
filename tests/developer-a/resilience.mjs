import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { setTimeout } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const repo = fileURLToPath(new URL('../../', import.meta.url))
const compose = ['compose', '--env-file', '.local/.env', '-f', 'infrastructure/developer-a.compose.yml',
  '-f', 'infrastructure/discovery/standalone.compose.yml']
const docker = (...args) => execFileSync('docker', [...compose, ...args], { cwd: repo, encoding: 'utf8', timeout: 85_000 }).trim()
const identity = 'http://127.0.0.1:8081'
const registry = 'http://127.0.0.1:8082'
const analyzer = () => 'http://' + docker('port', 'analyzer', '8083')
const run = randomUUID()
const email = 'resilience-' + run + '@example.test'
const password = randomUUID()
const results = []
const baselineSpec = await readFile(new URL('./fixtures/student-v1.yaml', import.meta.url))
const updatedSpec = await readFile(new URL('./fixtures/student-v2.json', import.meta.url))

async function request(origin, path, options = {}) {
  return fetch(origin + path, { ...options, signal: AbortSignal.timeout(6500) })
}

async function requireStatus(origin, path, expected, options = {}) {
  const response = await request(origin, path, options)
  assert.equal(response.status, expected, path + ' ' + (response.ok ? '' : await response.clone().text()))
  return response
}

function post(body, headers = {}) {
  return { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) }
}

async function monitor(action, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await action()
    if (value) return value
    await setTimeout(700)
  }
  throw new Error('Recovery condition did not occur within ' + timeoutMs + ' ms.')
}

async function job(id, states) {
  const base = analyzer()
  return monitor(async () => {
    const response = await request(base, '/analyses/' + id, { headers }).catch(() => null)
    if (!response?.ok) return null
    const analysis = await response.json()
    assert.notEqual(analysis.status, 'FAILED', JSON.stringify(analysis))
    return states.includes(analysis.status) ? analysis : null
  })
}

async function upload(label, content, filename, analyze = true) {
  const body = new FormData()
  body.set('versionLabel', label)
  body.set('analyze', String(analyze))
  body.set('file', new Blob([content]), filename)
  return (await requireStatus(registry, versions, 201, { method: 'POST', body, headers })).json()
}

function queueDepth() {
  const lines = docker('exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages_ready')
  const row = lines.split(/\r?\n/).find(line => line.startsWith('analyzer.version-registered.v1\t'))
  assert(row, 'Analyzer queue was not listed')
  return Number(row.split('\t')[1])
}

async function health(origin) {
  await monitor(async () => (await request(origin, '/actuator/health').catch(() => null))?.status === 200)
}

async function scenario(name, run) {
  const start = Date.now()
  try {
    const details = await run()
    results.push({ name, status: 'PASSED', durationMs: Date.now() - start, ...details })
  } catch (error) {
    results.push({ name, status: 'FAILED', durationMs: Date.now() - start, error: error.message })
    throw error
  }
}

await requireStatus(identity, '/auth/register', 201, post({ email, password, displayName: 'Recovery test', role: 'API_OWNER' }))
const login = await requireStatus(identity, '/auth/login', 200, post({ email, password }, { 'X-Sentinel-CSRF': '1' }))
const headers = { Authorization: 'Bearer ' + (await login.json()).accessToken }
const api = await (await requireStatus(registry, '/apis', 201, post({ name: 'Recovery test ' + run }, headers))).json()
const versions = '/apis/' + api.id + '/versions'
const baseline = await upload('v1', baselineSpec, 'baseline.yaml', false)
const known = await upload('v2', updatedSpec, 'update.json')
await job(known.analysis.analysisId, ['COMPLETED'])

try {
  await scenario('analyzer-stopped', async () => {
    docker('stop', 'analyzer')
    let next
    let ready
    try {
      next = await upload('v3', updatedSpec, 'analyzer-outage.json')
      await requireStatus(identity, '/auth/me', 200, { headers })
      await requireStatus(registry, versions + '/' + next.version.id, 200, { headers })
      ready = await monitor(() => queueDepth(), 25_000)
    } finally {
      docker('start', 'analyzer')
    }
    const resumed = Date.now()
    await job(next.analysis.analysisId, ['COMPLETED'])
    return { queuedMessages: ready, recoveryMs: Date.now() - resumed, versionId: next.version.id }
  })

  await scenario('rabbitmq-stopped', async () => {
    docker('stop', 'rabbitmq')
    let next
    try {
      next = await upload('v4', updatedSpec, 'broker-outage.json')
      await requireStatus(identity, '/auth/me', 200, { headers })
      await requireStatus(analyzer(), '/analyses/' + known.analysis.analysisId + '/report', 200, { headers })
      assert.match(next.analysis.analysisId, /^[a-f0-9-]{36}$/)
      const sql = "select count(*) from registry_db.outbox_events where published_at is null and "
        + "(convert_from(body,'UTF8')::jsonb #>> '{payload,analysisId}') = '" + next.analysis.analysisId + "'"
      const count = docker('exec', '-T', 'postgres', 'psql', '-At', '-U', 'postgres', '-d', 'sentinel', '-c', sql)
      assert.equal(count, '1')
    } finally {
      docker('start', 'rabbitmq')
    }
    const resumed = Date.now()
    await job(next.analysis.analysisId, ['COMPLETED'])
    return { persistedOutboxRows: 1, recoveryMs: Date.now() - resumed, versionId: next.version.id }
  })

  await scenario('registry-stopped', async () => {
    docker('stop', 'analyzer')
    let next
    let retried
    try {
      next = await upload('v5', updatedSpec, 'registry-outage.json')
      await monitor(() => queueDepth(), 25_000)
      docker('stop', 'registry')
    } finally {
      docker('start', 'analyzer')
    }
    try {
      retried = await job(next.analysis.analysisId, ['RETRY_PENDING'])
      await requireStatus(identity, '/auth/me', 200, { headers })
      await requireStatus(analyzer(), '/analyses/' + known.analysis.analysisId + '/report', 200, { headers })
    } finally {
      docker('start', 'registry')
    }
    const resumed = Date.now()
    await health(registry)
    await job(next.analysis.analysisId, ['COMPLETED'])
    const oldVersion = await requireStatus(registry, versions + '/' + baseline.version.id + '/spec', 200, { headers })
    assert.deepEqual(Buffer.from(await oldVersion.arrayBuffer()), baselineSpec)
    return { attemptsDuringOutage: retried.attemptCount, recoveryMs: Date.now() - resumed, priorSpecSurvivedRestart: true }
  })
} finally {
  const evidence = new URL('../../.local/evidence/', import.meta.url)
  await mkdir(evidence, { recursive: true })
  const report = { generatedAt: new Date().toISOString(), results }
  await writeFile(new URL('resilience.json', evidence), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report))
}
