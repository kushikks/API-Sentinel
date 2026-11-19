import type { ReactNode } from 'react'
import { ApiError } from './session'

export function Feedback({ error }: { error: unknown }) {
  if (!error) return null
  return <div role="alert" className="feedback error">
    <div>{error instanceof Error ? error.message : 'The request failed.'}</div>
    {error instanceof ApiError && error.fields.length > 0 &&
      <ul>{error.fields.map(field => <li key={field.field + field.message}>{field.field} {field.message}</li>)}</ul>}
  </div>
}

export function Pending({ children = 'Loading…' }: { children?: ReactNode }) {
  return <div role="status" className="pending"><span className="spinner" aria-hidden="true" />{children}</div>
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="empty"><span className="empty-mark" aria-hidden="true" /><h2>{title}</h2>{children && <p>{children}</p>}</div>
}

export function Pagination({ page, hasNext, change }: { page: number; hasNext: boolean; change: (page: number) => void }) {
  if (page === 0 && !hasNext) return null
  return <nav className="pagination" aria-label="Results pages">
    <button type="button" className="button secondary" disabled={page === 0} onClick={() => change(page - 1)}>Previous</button>
    <span>Page {page + 1}</span>
    <button type="button" className="button secondary" disabled={!hasNext} onClick={() => change(page + 1)}>Next</button>
  </nav>
}

export function dateLabel(date: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(date))
}

export function Brand() {
  return <span className="brand"><span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>API Sentinel</span>
}
