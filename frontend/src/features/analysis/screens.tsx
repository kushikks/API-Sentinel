import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Link, useLocation, useParams, useSearchParams } from 'react-router'
import { ApiError, canWrite, useSession, type Page } from '../auth/session'
import { Empty, Feedback, Pagination, Pending, dateLabel } from '../auth/ui'
import { WorkspaceHero, WorkspaceIcon } from '../auth/workspace-ui'
import type { Api, Version } from '../apis/types'
import { statusNames, type Job, type JobState } from './jobs'
import { MigrationSuggestions } from './MigrationSuggestions'

type Severity = 'BREAKING' | 'POTENTIALLY_BREAKING' | 'NON_BREAKING'
type Finding = {
  findingId: string; severity: Severity; category: string; httpMethod: string | null; path: string | null; jsonPath: string;
  description: string; oldValue: string | null; newValue: string | null; ruleId: string
}

const severityNames: Record<Severity, string> = {
  BREAKING: 'Breaking', POTENTIALLY_BREAKING: 'Potentially breaking', NON_BREAKING: 'Non-breaking',
}

const terminal = (status?: JobState) => status === 'COMPLETED' || status === 'FAILED'
const readable = (value: string) => value.toLowerCase().replaceAll('_', ' ')
const isMissing = (error: unknown) => error instanceof ApiError && error.status === 404

export function AnalysisHistory() {
  const [search] = useSearchParams()
  const apiId = search.get('apiId')
  return <History key={apiId ?? 'all'} apiId={apiId} />
}

function History({ apiId }: { apiId: string | null }) {
  const { client } = useSession()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('')
  const params = new URLSearchParams({ page: String(page) })
  if (status) params.set('status', status)
  if (apiId) params.set('apiId', apiId)
  const api = useQuery({
    queryKey: ['api', apiId],
    queryFn: ({ signal }) => client.json<Api>('/apis/' + apiId, { signal }),
    enabled: Boolean(apiId),
  })
  const history = useQuery({
    queryKey: ['analyses', apiId, status, page],
    queryFn: ({ signal }) => client.json<Page<Job>>('/analyses?' + params, { signal }),
    refetchInterval: 3000,
  })
  return <>
    <WorkspaceHero title="Analysis history"
      description={api.data ? 'Comparisons for ' + api.data.name + '.' : 'Review OpenAPI comparisons and find the changes that need attention.'}>
      {apiId && <Link className="button secondary" to="/comparisons">Show all analyses</Link>}
    </WorkspaceHero>
    <section className="surface" aria-labelledby="analysis-runs">
      <header className="panel-heading"><h2 id="analysis-runs"><WorkspaceIcon name="activity" /> Analysis runs</h2></header>
      <div className="toolbar">
        <label>Job status<select value={status} onChange={event => { setStatus(event.target.value); setPage(0) }}>
          <option value="">All statuses</option>
          {Object.entries(statusNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
      </div>
      {history.isPending && <Pending />}
      <Feedback error={history.error} />
      {history.data && (history.data.items.length > 0 ? <>
        <div className="table-scroll"><table>
          <thead><tr><th scope="col">Analysis</th><th scope="col">Status</th><th scope="col">Breaking</th><th scope="col">Submitted</th></tr></thead>
          <tbody>{history.data.items.map(job => <HistoryRow key={job.id} job={job} />)}</tbody>
        </table></div>
        <Pagination page={page} hasNext={history.data.hasNext} change={setPage} />
      </> : <Empty title="No matching analyses">{apiId ? 'Submit a comparison from the API detail page.' : 'Upload an OpenAPI version or compare existing versions.'}</Empty>)}
    </section>
  </>
}

function HistoryRow({ job }: { job: Job }) {
  const { client } = useSession()
  const api = useQuery({
    queryKey: ['api', job.apiId],
    queryFn: ({ signal }) => client.json<Api>('/apis/' + job.apiId, { signal }),
  })
  return <tr>
    <td><Link className="row-link" to={'/comparisons/' + job.id}>{api.data?.name ?? 'Open analysis'}</Link>
      <div className="mono muted row-detail" title={job.id}>{job.id.slice(0, 12)}</div></td>
    <td><JobBadge status={job.status} /></td>
    <td>{job.outcome === 'COMPARED'
      ? <span className={'badge' + (job.breakingCount > 0 ? ' breaking' : '')}>{job.breakingCount}</span>
      : '—'}</td><td><time dateTime={job.createdAt}>{dateLabel(job.createdAt)}</time></td>
  </tr>
}

export function AnalysisDetail() {
  const { analysisId } = useParams()
  const location = useLocation()
  if (!analysisId) return <Empty title="Analysis not found" />
  return <AnalysisContent key={analysisId} analysisId={analysisId} submitted={location.state?.submitted === true} />
}

function AnalysisContent({ analysisId, submitted }: { analysisId: string; submitted: boolean }) {
  const { client } = useSession()
  const url = '/analyses/' + analysisId
  const query = useQuery({
    queryKey: ['analysis', analysisId], queryFn: ({ signal }) => client.json<Job>(url, { signal }),
    refetchInterval: query => {
      if (terminal(query.state.data?.status)) return false
      if (isMissing(query.state.error) && !submitted) return false
      return 1500
    },
  })
  const job = query.data
  const apiId = job?.apiId
  const api = useQuery({
    queryKey: ['api', apiId], enabled: Boolean(apiId),
    queryFn: ({ signal }) => client.json<Api>('/apis/' + apiId, { signal }),
  })
  const newVersion = useVersion(apiId, job?.newVersionId)
  const baseline = useVersion(apiId, job?.baselineVersionId)
  const download = useMutation({ mutationFn: () => client.download(url + '/report', 'compatibility-' + analysisId + '.json') })
  const back = apiId ? '/catalog/' + apiId : '/comparisons'
  return <>
    <Link to={back} className="back-link">← {apiId ? 'API details' : 'Analysis history'}</Link>
    <header className="page-heading detail-heading"><div><h1>{api.data?.name ?? 'Compatibility analysis'}</h1>
      {job && <>
        <div className="version-pair">
          <span className="version-chip mono">{job.baselineVersionId ? baseline.data?.versionLabel ?? job.baselineVersionId.slice(0, 8) : 'No baseline'}</span>
          <span aria-hidden="true">→</span><span className="sr-only">to</span>
          <span className="version-chip mono version-target">{newVersion.data?.versionLabel ?? job.newVersionId.slice(0, 8)}</span>
        </div>
        <div className="job-meta" aria-live="polite"><JobBadge status={job.status} /><span>Submitted {dateLabel(job.createdAt)}</span>
          <span>Attempts {job.attemptCount}</span></div>
      </>}</div>
      {job?.status === 'COMPLETED' && <button className="button secondary" onClick={() => download.mutate()}
        disabled={download.isPending}>{download.isPending ? 'Downloading…' : 'Download report'}</button>}
    </header>
    <Feedback error={download.error} />
    {query.isPending && <Pending />}
    {query.error && (isMissing(query.error) && submitted ?
      <div className="surface pad"><Pending>Submitted. Waiting for Analyzer to receive the job…</Pending></div>
      : <Feedback error={query.error} />)}
    {job && <>
      {job.error && <div role="status" className={'feedback ' + (job.status === 'FAILED' ? 'error' : '')}>{job.error}</div>}
      {job.status === 'COMPLETED' && (job.outcome === 'NO_BASELINE'
        ? <div className="surface"><Empty title="Baseline recorded">Upload another version to compare compatibility.</Empty></div>
        : <Findings url={url} job={job} />)}
    </>}
  </>
}

function useVersion(apiId?: string, versionId?: string | null) {
  const { client } = useSession()
  return useQuery({
    queryKey: ['version', apiId, versionId], enabled: Boolean(apiId && versionId),
    queryFn: ({ signal }) => client.json<Version>('/apis/' + apiId + '/versions/' + versionId, { signal }),
  })
}

function Findings({ url, job }: { url: string; job: Job }) {
  const { client, session } = useSession()
  const [severity, setSeverity] = useState<Severity | ''>('')
  const [categoryInput, setCategoryInput] = useState('')
  const [category, setCategory] = useState('')
  const [page, setPage] = useState(0)
  const params = new URLSearchParams({ page: String(page) })
  if (severity) params.set('severity', severity)
  if (category) params.set('category', category)
  const query = useQuery({
    queryKey: ['findings', url, severity, category, page],
    queryFn: ({ signal }) => client.json<Page<Finding>>(url + '/findings?' + params, { signal }),
  })
  function filterSeverity(value: Severity | '') {
    setSeverity(value)
    setPage(0)
  }
  return <>
    <FindingSummary job={job} selection={severity} onSelect={filterSeverity} />
    {canWrite(session.user) && <MigrationSuggestions analysisId={job.id}
      hasEligibleFindings={job.breakingCount + job.potentiallyBreakingCount > 0} />}
    <section className="surface" aria-labelledby="findings-title">
      <div className="panel-heading findings-heading"><div>
        <h2 id="findings-title"><WorkspaceIcon name="findings" /> Compatibility findings</h2>
        <p>Findings describe contract changes. Consumer impact depends on how each consumer uses the API.</p>
      </div></div>
      <form className="toolbar findings-toolbar" onSubmit={event => {
        event.preventDefault()
        setCategory(categoryInput.trim().toUpperCase())
        setPage(0)
      }}>
        <label>Severity<select value={severity} onChange={event => filterSeverity(event.target.value as Severity | '')}>
          <option value="">All severities</option>
          {Object.entries(severityNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label>Category<input value={categoryInput} maxLength={80} placeholder="ENDPOINT_REMOVED"
          onChange={event => setCategoryInput(event.target.value)} /></label>
        <button className="button secondary">Filter</button>
        {category && <button type="button" className="text-button" onClick={() => { setCategory(''); setCategoryInput(''); setPage(0) }}>Clear category</button>}
      </form>
      {query.isPending && <Pending />}
      <Feedback error={query.error} />
      {query.data && (query.data.items.length > 0 ? <>
        <ul className="finding-list">{query.data.items.map(finding => <li className="finding" key={finding.findingId}>
          <div className="finding-title">
            <span className={'badge ' + finding.severity.toLowerCase()}>{severityNames[finding.severity]}</span>
            <span className="muted">{readable(finding.category)}</span>
          </div>
          <h3>{finding.description}</h3>
          {finding.path && <p className="endpoint mono">{finding.httpMethod && <strong>{finding.httpMethod}</strong>} {finding.path}</p>}
          <details><summary>Finding details</summary>
            <dl><dt>Contract path</dt><dd className="mono">{finding.jsonPath}</dd>
              <dt>Rule</dt><dd className="mono">{finding.ruleId}</dd>
              <dt>Before</dt><dd className="mono">{finding.oldValue ?? '—'}</dd>
              <dt>After</dt><dd className="mono">{finding.newValue ?? '—'}</dd></dl>
          </details>
        </li>)}</ul>
        <Pagination page={page} hasNext={query.data.hasNext} change={setPage} />
      </> : <Empty title={severity || category ? 'No matching findings' : 'No compatibility changes found'} />)}
    </section>
  </>
}

function JobBadge({ status }: { status: JobState }) {
  return <span className={'badge ' + status.toLowerCase()}>{statusNames[status]}</span>
}

function FindingSummary({ job, selection, onSelect }: { job: Job; selection: Severity | ''; onSelect: (severity: Severity | '') => void }) {
  const counts: [Severity, number][] = [
    ['BREAKING', job.breakingCount],
    ['POTENTIALLY_BREAKING', job.potentiallyBreakingCount],
    ['NON_BREAKING', job.nonBreakingCount],
  ]
  const total = counts.reduce((sum, [, value]) => sum + value, 0)
  return <section className="surface finding-overview" aria-labelledby="finding-overview">
    <header className="panel-heading">
      <h2 id="finding-overview"><WorkspaceIcon name="activity" /> Change summary</h2>
      <span className="muted">{total} {total === 1 ? 'finding' : 'findings'}</span>
    </header>
    <div className="severity-track" aria-hidden="true">
      {counts.filter(([, value]) => value > 0).map(([key, value]) =>
        <span key={key} className={key.toLowerCase()} style={{ flexGrow: value }} />)}
    </div>
    <div className="count-grid" role="group" aria-label="Filter findings by severity">
      {counts.map(([key, value]) => <button key={key} type="button" className={'count ' + key.toLowerCase()}
        aria-label={severityNames[key] + ' findings'} aria-pressed={selection === key}
        onClick={() => onSelect(selection === key ? '' : key)}>
        <span>{severityNames[key]}</span><strong>{value}</strong>
      </button>)}
    </div>
  </section>
}
