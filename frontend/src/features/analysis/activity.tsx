import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useSession, type Page } from '../auth/session'
import { dateLabel, Feedback, Pending } from '../auth/ui'
import { WorkspaceIcon } from '../auth/workspace-ui'
import type { Api } from '../apis/types'
import { statusNames, type Job } from './jobs'

export function useRecentAnalyses() {
  const { client } = useSession()
  return useQuery({
    queryKey: ['analyses', 'recent'],
    queryFn: ({ signal }) => client.json<Page<Job>>('/analyses?size=5', { signal }),
    refetchInterval: 15_000,
  })
}

export function RecentAnalyses({ recent }: { recent: ReturnType<typeof useRecentAnalyses> }) {
  const jobs = recent.data?.items ?? []
  return <section className="surface activity-panel" aria-labelledby="recent-analyses">
    <header className="panel-heading">
      <h2 id="recent-analyses"><WorkspaceIcon name="activity" /> Recent analyses</h2>
      <Link className="compact-link" to="/comparisons">View history <span aria-hidden="true">↗</span></Link>
    </header>
    {recent.isPending && <Pending />}
    <Feedback error={recent.error} />
    {jobs.length > 0 ? <ul className="activity-list">
      {jobs.map(job => <ActivityEntry key={job.id} job={job} />)}
    </ul> : recent.isSuccess && <div className="activity-empty">
      <span className="activity-empty-icon"><WorkspaceIcon name="activity" /></span>
      <p>No analyses yet.</p>
      <small>New comparison runs will appear here.</small>
    </div>}
  </section>
}

function ActivityEntry({ job }: { job: Job }) {
  const { client } = useSession()
  const api = useQuery({
    queryKey: ['api', job.apiId],
    queryFn: ({ signal }) => client.json<Api>('/apis/' + job.apiId, { signal }),
  })
  const result = job.outcome === 'NO_BASELINE' ? 'Baseline recorded' : job.outcome === 'COMPARED'
    ? (job.breakingCount > 0 ? job.breakingCount + ' breaking' : 'No breaking findings')
    : null
  return <li><Link to={'/comparisons/' + job.id} className="activity-entry">
    <span className={'activity-dot ' + job.status.toLowerCase()} aria-hidden="true" />
    <div className="activity-copy">
      <strong>{api.data?.name ?? 'OpenAPI comparison'}</strong>
      <small><time dateTime={job.createdAt}>{dateLabel(job.createdAt)}</time></small>
      <div className="activity-tags">
        <span className={'badge ' + job.status.toLowerCase()}>{statusNames[job.status]}</span>
        {result && <span className={'activity-result' + (job.breakingCount > 0 ? ' has-breaking' : '')}>{result}</span>}
      </div>
    </div>
    <span className="activity-arrow" aria-hidden="true">↗</span>
  </Link></li>
}
