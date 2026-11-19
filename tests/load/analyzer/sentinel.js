import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const mode = __ENV.SCENARIO || 'jobs';
const jobs = Number(__ENV.JOBS || 100);
const clients = Number(__ENV.VUS || 100);
const identity = __ENV.IDENTITY_URL || 'http://identity:8081';
const registry = __ENV.REGISTRY_URL || 'http://registry:8082';
const analyzer = __ENV.ANALYZER_URL || 'http://analyzer:8083';
const completed = new Counter('jobs_completed');
const failed = new Counter('jobs_failed');
const elapsed = new Trend('job_turnaround_ms', true);
const validPoll = http.expectedStatuses(200, 404);

const scenarios = {
  volume: { executor: 'shared-iterations', vus: clients, iterations: Number(__ENV.REQUESTS || 10000), maxDuration: '5m' },
  sustained: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 50), timeUnit: '1s',
    duration: __ENV.DURATION || '120s', preAllocatedVUs: Math.min(20, clients), maxVUs: clients },
  jobs: { executor: 'shared-iterations', vus: Math.min(jobs, Number(__ENV.JOB_VUS || 12)), iterations: jobs, maxDuration: '10m' },
};

if (!scenarios[mode]) throw new Error('SCENARIO must be volume, sustained or jobs.');

export const options = {
  scenarios: { [mode]: scenarios[mode] },
  setupTimeout: '100s',
  summaryTrendStats: ['avg', 'min', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>=0.99'],
    ...(mode === 'volume' ? { iterations: ['count==' + scenarios.volume.iterations] } : {}),
    ...(mode === 'sustained' ? { 'http_req_duration{phase:read}': ['p(95)<1000'], dropped_iterations: ['count==0'] } : {}),
    ...(mode === 'jobs' ? { jobs_completed: ['count==' + jobs], jobs_failed: ['count==0'] } : {}),
  },
};

export function setup() {
  const run = Date.now() + '-' + Math.floor(Math.random() * 100000);
  const email = 'load-' + run + '@example.test';
  const password = 'Load-' + run + '-demo';
  const csrf = { 'X-Sentinel-CSRF': '1' };
  assertStatus(post(identity + '/auth/register', { email, password, displayName: 'k6 test', role: 'API_OWNER' }, {}, 'register'), 201);
  const token = assertStatus(post(identity + '/auth/login', { email, password }, csrf, 'login'), 200).json('accessToken');
  const headers = { Authorization: 'Bearer ' + token };
  const api = assertStatus(post(registry + '/apis', { name: 'Load test ' + run }, headers, 'create_api'), 201).json();
  const url = registry + '/apis/' + api.id + '/versions';
  const baseline = assertStatus(upload(url, 'baseline', contract(false), headers, false), 201).json('version.id');
  if (mode === 'jobs') return { baseline, headers, url };
  const version = assertStatus(upload(url, 'next', contract(true), headers, true), 201).json();
  const job = awaitJob(version.analysis.analysisId, headers, 80);
  if (!job || job.status !== 'COMPLETED') fail('Could not prepare the analysis read fixture.');
  return { updated: version.version.id, analysisId: job.id, headers, url };
}

export default function run(data) {
  if (mode !== 'jobs') {
    read(data);
    return;
  }
  const start = Date.now();
  const version = upload(data.url, 'change-' + exec.scenario.iterationInTest, contract(true), data.headers, false);
  if (!check(version, { 'version accepted': response => response.status === 201 })) {
    failed.add(1);
    return;
  }
  const created = post(data.url + '/' + version.json('version.id') + '/analyze', { baselineVersionId: data.baseline }, data.headers, 'submit_job');
  if (!check(created, { 'analysis accepted': response => response.status === 202 })) {
    failed.add(1);
    return;
  }
  const job = awaitJob(created.json('analysisId'), data.headers, 120);
  const valid = job && job.status === 'COMPLETED' && job.breakingCount >= 2;
  check(job, { 'job completed with breaking findings': () => Boolean(valid) });
  completed.add(valid ? 1 : 0);
  failed.add(valid ? 0 : 1);
  elapsed.add(Date.now() - start);
}

function read(data) {
  const index = exec.scenario.iterationInTest % 10;
  const request = name => ({ headers: data.headers, tags: { phase: 'read', name }, responseType: 'none' });
  let response;
  if (index < 3) response = http.get(registry + '/apis', request('list_apis'));
  else if (index < 5) response = http.get(data.url + '/' + data.updated, request('get_version'));
  else if (index < 7) response = http.get(analyzer + '/analyses/' + data.analysisId + '/findings', request('get_findings'));
  else if (index < 9) response = http.get(analyzer + '/analyses/' + data.analysisId + '/summary', request('get_summary'));
  else response = http.get(identity + '/auth/me', request('profile'));
  check(response, { 'authenticated read succeeds': result => result.status === 200 });
}

function upload(url, label, specification, headers, analyze) {
  return http.post(url, {
    versionLabel: label, file: http.file(JSON.stringify(specification), 'openapi.json', 'application/json'), analyze: String(analyze),
  }, { headers, tags: { name: 'upload_version' } });
}

function post(url, body, headers, name) {
  return http.post(url, JSON.stringify(body), { headers: { ...headers, 'Content-Type': 'application/json' }, tags: { name } });
}

function awaitJob(id, headers, seconds) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    const response = http.get(analyzer + '/analyses/' + id, { headers, responseCallback: validPoll, tags: { name: 'poll_analysis' }, timeout: '5s' });
    if (response.status === 200) {
      const job = response.json();
      if (job.status === 'COMPLETED' || job.status === 'FAILED') return job;
    }
    sleep(0.7);
  }
  return null;
}

function assertStatus(response, status) {
  if (response.status !== status) fail('Fixture setup failed for ' + response.request.method + ' with HTTP ' + response.status);
  return response;
}

function contract(next) {
  const spec = {
    openapi: '3.0.3', info: { title: 'Campus Service API', version: next ? '2.0.0' : '1.0.0' },
    paths: {}, components: { schemas: {} },
  };
  const resources = ['students', 'departments', 'semesters', 'modules', 'enrolments', 'rooms', 'facilities', 'instructors',
    'assessments', 'submissions', 'results', 'attendance', 'schedules', 'campuses', 'admissions', 'scholarships'];
  for (const resource of resources) {
    const model = resource[0].toUpperCase() + resource.slice(1) + 'Record';
    const properties = {
      department: { type: 'string', description: 'Department responsible for the record' },
      active: { type: 'boolean' },
      createdAt: { type: 'string', format: 'date-time' },
      lastModified: { type: 'string', format: 'date-time' },
      classifications: { type: 'array', items: { type: 'string', maxLength: 64 } },
      metadata: { type: 'object', properties: { region: { type: 'string' }, revision: { type: 'integer' },
        source: { type: 'string', enum: ['manual', 'import', 'service'] } } },
    };
    properties[resource === 'students' && !next ? 'studentId' : 'id'] = { type: 'integer', format: 'int64' };
    properties[resource === 'students' && next ? 'fullName' : 'name'] = { type: 'string' };
    spec.components.schemas[model] = { type: 'object', required: ['active', 'department'], properties };
    const reference = { $ref: '#/components/schemas/' + model };
    const result = schema => ({ description: 'Campus record response', content: { 'application/json': { schema } } });
    spec.paths['/api/' + resource] = { get: { operationId: 'list_' + resource, parameters: [
      { name: 'limit', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100 } },
      { name: 'cursor', in: 'query', schema: { type: 'string' } },
    ], responses: { '200': result({ type: 'array', items: reference }) } } };
    spec.paths['/api/' + resource + '/{id}'] = { get: { operationId: 'get_' + resource, parameters: [
      { name: 'id', in: 'path', required: true, schema: { type: 'integer', format: 'int64' } },
    ], responses: { '200': result(reference), '404': { description: 'Record does not exist' } } } };
  }
  return spec;
}

export function handleSummary(data) {
  const duration = data.state.testRunDurationMs;
  const count = data.metrics.jobs_completed?.values.count || 0;
  const summary = {
    generatedAt: new Date().toISOString(),
    scenario: mode,
    environment: __ENV.SENTINEL_TEST_ENV || 'local',
    analyzerReplicas: Number(__ENV.ANALYZER_REPLICAS || 1),
    configured: scenarios[mode],
    testDurationMs: duration,
    completedJobs: count,
    jobsPerSecond: mode === 'jobs' && duration > 0 ? count * 1000 / duration : null,
    specificationBytes: JSON.stringify(contract(false)).length,
    metrics: data.metrics,
  };
  return { stdout: JSON.stringify({
    scenario: mode, requests: data.metrics.http_reqs?.values, latency: data.metrics.http_req_duration?.values,
    errors: data.metrics.http_req_failed?.values, completedJobs: count, jobsPerSecond: summary.jobsPerSecond,
  }, null, 2) + '\n', [__ENV.SUMMARY_PATH || '/results/k6-' + mode + '.json']: JSON.stringify(summary, null, 2) };
}
