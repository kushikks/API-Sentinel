import { useState, type FormEvent } from 'react'
import { queryOptions, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { RecentAnalyses, useRecentAnalyses } from '../analysis/activity'
import { ApiError, canWrite, jsonBody, useSession, type Page } from '../auth/session'
import { Empty, Feedback, Pagination, Pending, dateLabel } from '../auth/ui'
import { WorkspaceHero, WorkspaceIcon, type IconName } from '../auth/workspace-ui'
import type { Api, Submission, Upload, Version } from './types'

function apiPage(client: ReturnType<typeof useSession>['client'], filter: string, page: number) {
  return queryOptions({
    queryKey: ['apis', filter, page],
    queryFn: ({ signal }) => client.json<Page<Api>>('/apis?' + new URLSearchParams({ q: filter, page: String(page) }), { signal }),
  })
}

export function ApiCatalog() {
  const { client, session } = useSession()
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const [view, setView] = useState<'cards' | 'table'>('cards')
  const query = useQuery(apiPage(client, filter, page))
  const workspaceApis = useQuery(apiPage(client, '', 0))
  const recent = useRecentAnalyses()
  const breakingCount = recent.data?.items.reduce((count, job) => count + job.breakingCount, 0)
  const entries = query.data?.items ?? []
  return <>
    <WorkspaceHero title="API registry" description="Track OpenAPI versions and catch breaking changes before release.">
      {canWrite(session.user) && <button className="button primary" aria-expanded={createOpen}
        onClick={() => setCreateOpen(open => !open)}>
        <span className="action-plus" aria-hidden="true">{createOpen ? '−' : '+'}</span>{createOpen ? 'Close form' : 'Register API'}
      </button>}
    </WorkspaceHero>
    {createOpen && <ApiForm close={() => setCreateOpen(false)} />}
    <dl className="registry-metrics" aria-label="Registry overview">
      <RegistryMetric icon="contracts" label="Tracked APIs" value={workspaceApis.data
        ? String(workspaceApis.data.items.length) + (workspaceApis.data.hasNext ? '+' : '') : '—'} />
      <RegistryMetric icon="activity" label="Recent runs" value={recent.data?.items.length ?? '—'} note="Latest five comparisons" />
      <RegistryMetric icon="findings" label="Breaking findings" value={breakingCount ?? '—'} note="Across recent runs"
        highlight={Boolean(breakingCount)} />
    </dl>
    <div className="catalog-columns">
      <section className="surface catalog-panel" aria-labelledby="registered-apis">
        <header className="panel-heading">
          <h2 id="registered-apis"><WorkspaceIcon name="contracts" /> Registered APIs</h2>
          {entries.length > 0 && <div className="view-switch" role="group" aria-label="Registry layout">
            <button type="button" aria-label="Card view" title="Card view" aria-pressed={view === 'cards'} onClick={() => setView('cards')}>
              <WorkspaceIcon name="grid" />
            </button>
            <button type="button" aria-label="Table view" title="Table view" aria-pressed={view === 'table'} onClick={() => setView('table')}>
              <WorkspaceIcon name="list" />
            </button>
          </div>}
        </header>
        <form className="toolbar catalog-toolbar" onSubmit={event => { event.preventDefault(); setFilter(search.trim()); setPage(0) }}>
          <label className="search-field catalog-search"><span className="sr-only">Search APIs</span><WorkspaceIcon name="search" />
            <input type="search" value={search} maxLength={100} onChange={event => setSearch(event.target.value)}
              placeholder="Name or description" /></label>
          <button className="button secondary">Search</button>
          {filter && <button type="button" className="text-button" onClick={() => { setSearch(''); setFilter(''); setPage(0) }}>Clear</button>}
        </form>
        {query.isPending && <Pending />}
        <Feedback error={query.error} />
        {query.data && <>
          {entries.length > 0 ? view === 'cards' ? <ApiCards entries={entries} /> : <ApiTable entries={entries} />
            : <Empty title={filter ? 'No matching APIs' : 'No registered APIs'}>
              {filter ? 'Try a different search.' : canWrite(session.user) ? 'Register an API to upload its first contract.' : 'Your account has no API records.'}
            </Empty>}
          <Pagination page={page} hasNext={query.data.hasNext} change={setPage} />
        </>}
      </section>
      <RecentAnalyses recent={recent} />
    </div>
  </>
}

function RegistryMetric({ icon, label, value, note, highlight }: {
  icon: IconName; label: string; value: string | number; note?: string; highlight?: boolean
}) {
  return <div className={highlight ? 'registry-metric surface metric-attention' : 'registry-metric surface'}>
    <dt>{label}<span className="metric-icon"><WorkspaceIcon name={icon} /></span></dt>
    <dd className="metric-value">{value}</dd>
    {note && <dd className="metric-note">{note}</dd>}
  </div>
}

function ApiCards({ entries }: { entries: Api[] }) {
  return <ul className="api-cards">
    {entries.map(api => <li key={api.id}>
      <Link to={'/catalog/' + api.id} className="api-card">
        <div className="api-card-title"><span className="api-card-icon"><WorkspaceIcon name="contracts" /></span>
          <strong>{api.name}</strong><span className="api-card-arrow" aria-hidden="true">↗</span></div>
        {api.description && <p>{api.description}</p>}
        <div className="api-card-meta">
          {api.baseUrl && <span className="mono">{api.baseUrl}</span>}
          <time dateTime={api.updatedAt}>{dateLabel(api.updatedAt)}</time>
        </div>
      </Link>
    </li>)}
  </ul>
}

function ApiTable({ entries }: { entries: Api[] }) {
  return <div className="table-scroll"><table>
    <thead><tr><th scope="col">API</th><th scope="col">Base URL</th><th scope="col">Last updated</th></tr></thead>
    <tbody>{entries.map(api => <tr key={api.id}>
      <td><Link className="row-link" to={'/catalog/' + api.id}>{api.name}</Link>
        {api.description && <div className="muted row-detail">{api.description}</div>}</td>
      <td className="mono wrap">{api.baseUrl ?? '—'}</td><td>{dateLabel(api.updatedAt)}</td>
    </tr>)}</tbody>
  </table></div>
}

function ApiForm({ api, close }: { api?: Api; close: () => void }) {
  const { client } = useSession()
  const cache = useQueryClient()
  const navigate = useNavigate()
  const mutation = useMutation({
    mutationFn: (fields: FormData) => client.json<Api>(api ? '/apis/' + api.id : '/apis', {
      method: api ? 'PATCH' : 'POST',
      ...jsonBody({ name: fields.get('name'), description: fields.get('description'), baseUrl: fields.get('baseUrl') }),
    }),
    onSuccess: async value => {
      cache.setQueryData(['api', value.id], value)
      await cache.invalidateQueries({ queryKey: ['apis'] })
      close()
      if (!api) navigate('/catalog/' + value.id, { state: { created: true } })
    },
  })
  return <section className="surface pad action-panel">
    <h2>{api ? 'Edit API details' : 'Register an API'}</h2>
    <form className="form" onSubmit={event => { event.preventDefault(); mutation.mutate(new FormData(event.currentTarget)) }}>
      <div className="form-grid">
        <label>API name<input name="name" defaultValue={api?.name} required maxLength={100} autoFocus={!api} /></label>
        <label>Base URL <small>Optional</small><input type="url" name="baseUrl" defaultValue={api?.baseUrl ?? ''}
          placeholder="https://api.example.com" maxLength={2048} /></label>
      </div>
      <label>Description <small>Optional</small><textarea name="description" rows={3} defaultValue={api?.description ?? ''} maxLength={1000} /></label>
      <Feedback error={mutation.error} />
      <div className="actions"><button className="button primary" disabled={mutation.isPending}>
        {mutation.isPending ? 'Saving…' : api ? 'Save changes' : 'Create API'}</button>
        <button type="button" className="button secondary" onClick={close}>Cancel</button></div>
    </form>
  </section>
}

export function ApiDetail() {
  const { apiId } = useParams()
  if (!apiId) return <Empty title="API not found" />
  return <ApiDetailContent key={apiId} apiId={apiId} />
}

function ApiDetailContent({ apiId }: { apiId: string }) {
  const { client, session } = useSession()
  const cache = useQueryClient()
  const [edit, setEdit] = useState(false)
  const [upload, setUpload] = useState(false)
  const [notice, setNotice] = useState('')
  const detail = useQuery({ queryKey: ['api', apiId], queryFn: ({ signal }) => client.json<Api>('/apis/' + apiId, { signal }) })
  const versions = useInfiniteQuery({
    queryKey: ['versions', apiId], initialPageParam: 0, enabled: Boolean(detail.data),
    queryFn: ({ pageParam, signal }) => client.json<Page<Version>>('/apis/' + apiId + '/versions?page=' + pageParam, { signal }),
    getNextPageParam: last => last.hasNext ? last.page + 1 : undefined,
  })
  const values = versions.data?.pages.flatMap(page => page.items) ?? []
  if (detail.isPending) return <Pending />
  if (detail.error || !detail.data) return <><Link to="/catalog" className="back-link">← API registry</Link><Feedback error={detail.error} /></>
  const api = detail.data
  const canEdit = canWrite(session.user) && (session.user?.id === api.ownerId || Boolean(session.user?.roles.includes('ADMIN')))
  return <>
    <Link to="/catalog" className="back-link">← API registry</Link>
    <header className="page-heading detail-heading">
      <div><h1>{api.name}</h1>{api.description && <p>{api.description}</p>}
        {api.baseUrl && <div className="mono base-url wrap">{api.baseUrl}</div>}</div>
      {canEdit && <div className="actions">
        <button className="button secondary" onClick={() => setEdit(value => !value)} aria-expanded={edit}>Edit details</button>
        <button className="button primary" onClick={() => setUpload(value => !value)} aria-expanded={upload}>Upload version</button>
      </div>}
    </header>
    {notice && <p role="status" className="feedback">{notice}</p>}
    {edit && <ApiForm api={api} close={() => setEdit(false)} />}
    {upload && <UploadForm api={api} close={() => setUpload(false)} onUploaded={async result => {
      await cache.invalidateQueries({ queryKey: ['versions', api.id] })
      setUpload(false)
      setNotice('Version ' + result.version.versionLabel + ' uploaded.' + (result.analysis ? ' Analysis submitted.' : ''))
    }} />}
    <div className={'version-layout' + (canEdit && values.length > 0 ? ' with-compare' : '')}>
      <section className="surface" aria-labelledby="versions-title">
        <header className="panel-heading">
          <h2 id="versions-title"><WorkspaceIcon name="contracts" /> Version history</h2>
          <Link className="compact-link" to={'/comparisons?apiId=' + api.id}>View analyses <span aria-hidden="true">↗</span></Link>
        </header>
        {versions.isPending && <Pending />}
        <Feedback error={versions.error} />
        {versions.data && (values.length > 0 ? <VersionTable apiId={api.id} versions={values} /> :
          <Empty title="No versions yet">Upload a JSON or YAML specification to create the first version.</Empty>)}
        {versions.hasNextPage && <div className="section-footer"><button className="button secondary"
          onClick={() => void versions.fetchNextPage()} disabled={versions.isFetchingNextPage}>
          {versions.isFetchingNextPage ? 'Loading…' : 'Load older versions'}</button></div>}
      </section>
      {canEdit && values.length > 0 && <CompareForm apiId={api.id} versions={values} />}
    </div>
  </>
}

function UploadForm({ api, close, onUploaded }: { api: Api; close: () => void; onUploaded: (result: Upload) => Promise<void> }) {
  const { client } = useSession()
  const submit = useMutation({
    mutationFn: (input: FormData) => {
      const file = input.get('file')
      if (!(file instanceof File) || file.size === 0) throw new Error('Choose an OpenAPI file.')
      if (file.size > 1_048_576) throw new Error('The maximum file size is 1 MiB.')
      input.set('analyze', input.has('analyze') ? 'true' : 'false')
      return client.json<Upload>('/apis/' + api.id + '/versions', { method: 'POST', body: input })
    },
    onSuccess: onUploaded,
  })
  return <section className="surface pad action-panel">
    <h2>Upload an OpenAPI version</h2>
    <form className="form" onSubmit={event => { event.preventDefault(); submit.mutate(new FormData(event.currentTarget)) }}>
      <div className="form-grid">
        <label>Version label<input name="versionLabel" placeholder="1.2.0" maxLength={80} required /></label>
        <label>Specification file<input name="file" type="file" accept=".json,.yaml,.yml" required />
          <small>OpenAPI 3.0 or 3.1, up to 1 MiB</small></label>
      </div>
      <label className="check"><input type="checkbox" name="analyze" defaultChecked /> Submit for analysis after upload</label>
      <Feedback error={submit.error} />
      <div className="actions"><button className="button primary" disabled={submit.isPending}>{submit.isPending ? 'Uploading…' : 'Save version'}</button>
        <button type="button" className="button secondary" onClick={close}>Cancel</button></div>
    </form>
  </section>
}

function VersionTable({ apiId, versions }: { apiId: string; versions: Version[] }) {
  const { client } = useSession()
  const download = useMutation({
    mutationFn: (version: Version) => client.download('/apis/' + apiId + '/versions/' + version.id + '/spec',
      'openapi-' + version.id + (version.contentType === 'application/json' ? '.json' : '.yaml')),
  })
  const status = useMutation({
    mutationFn: (version: string) => client.json<Latest>('/apis/' + apiId + '/versions/' + version + '/analysis'),
  })
  return <>
    <Feedback error={download.error} />
    <div className="table-scroll"><table>
      <thead><tr><th scope="col">Version</th><th scope="col">Format</th><th scope="col">Uploaded</th><th scope="col">Actions</th></tr></thead>
      <tbody>{versions.map(version => <tr key={version.id}>
        <td><strong>{version.versionLabel}</strong><div className="mono muted hash" title={version.specificationHash}>
          {version.specificationHash.slice(0, 12)}</div></td>
        <td>{version.contentType === 'application/json' ? 'JSON' : 'YAML'}</td>
        <td>{dateLabel(version.createdAt)}</td>
        <td><div className="table-actions">
          <button className="text-button" onClick={() => download.mutate(version)} disabled={download.isPending}>
            Download<span className="sr-only"> {version.versionLabel}</span></button>
          <button className="text-button" onClick={() => status.mutate(version.id)} disabled={status.isPending}>
            Analysis status<span className="sr-only"> for {version.versionLabel}</span></button>
        </div></td>
      </tr>)}</tbody>
    </table></div>
    {status.isPending && <div className="section-footer"><Pending>Checking analysis status…</Pending></div>}
    {status.error && <div className="section-footer"><Feedback error={status.error instanceof ApiError && status.error.status === 404
      ? new Error('This version has no analysis submission.') : status.error} /></div>}
    {status.data && <div className="section-footer status-result" role="status">
      <span>{status.data.job?.status.replaceAll('_', ' ') ?? (status.data.analyzerAvailable ? 'Submitted, waiting for Analyzer' : 'Submitted, Analyzer unavailable')}</span>
      <Link to={'/comparisons/' + status.data.submission.analysisId} state={{ submitted: true }}>Open analysis</Link>
    </div>}
  </>
}

type Latest = { submission: Submission; analyzerAvailable: boolean; job: { status: string } | null }

function CompareForm({ apiId, versions }: { apiId: string; versions: Version[] }) {
  const { client } = useSession()
  const navigate = useNavigate()
  const [newId, setNewId] = useState('')
  const [baseline, setBaseline] = useState('')
  const update = newId || versions[0].id
  const submit = useMutation({
    mutationFn: () => client.json<Submission>('/apis/' + apiId + '/versions/' + update + '/analyze', {
      method: 'POST', ...jsonBody({ baselineVersionId: baseline || null }),
    }),
    onSuccess: result => navigate('/comparisons/' + result.analysisId, { state: { submitted: true } }),
  })
  function run(event: FormEvent) {
    event.preventDefault()
    submit.mutate()
  }
  return <section className="surface pad action-panel compare-panel">
    <h2><WorkspaceIcon name="activity" /> Compare versions</h2>
    <form className="form" onSubmit={run}>
      <div className="form-grid">
        <label>Baseline version<select value={baseline} onChange={event => setBaseline(event.target.value)}>
          <option value="">Previous uploaded version</option>
          {versions.filter(version => version.id !== update).map(version =>
            <option key={version.id} value={version.id}>{version.versionLabel}</option>)}
        </select></label>
        <label>New version<select value={update} onChange={event => {
          const chosen = event.target.value
          setNewId(chosen)
          if (baseline === chosen) setBaseline('')
        }}>
          {versions.map(version => <option key={version.id} value={version.id}>{version.versionLabel}</option>)}
        </select></label>
      </div>
      <Feedback error={submit.error} />
      <div className="actions"><button className="button primary" disabled={submit.isPending}>{submit.isPending ? 'Submitting…' : 'Run comparison'}</button></div>
    </form>
  </section>
}
