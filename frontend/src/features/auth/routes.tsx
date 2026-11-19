import { useMutation } from '@tanstack/react-query'
import { Link, NavLink, Navigate, Outlet, useLocation, type RouteObject } from 'react-router'
import { ApiCatalog, ApiDetail } from '../apis/screens'
import { AnalysisDetail, AnalysisHistory } from '../analysis/screens'
import { SignIn, SignUp } from './SignIn'
import { useSession } from './session'
import { Brand, Feedback, Pending } from './ui'
import { ThemeSwitch } from './theme'
import './sentinel.css'
import './workspace.css'

export function ProtectedRoute() {
  const { session } = useSession()
  const location = useLocation()
  if (session.status === 'checking') return <Pending>Checking your session…</Pending>
  if (session.status !== 'signed-in') return <Navigate to="/sign-in" replace state={{ from: location.pathname + location.search }} />
  return <Outlet />
}

export function DeveloperAWorkspace() {
  const { client, session } = useSession()
  const { pathname } = useLocation()
  const logout = useMutation({ mutationFn: () => client.logout() })
  const user = session.user
  const analyses = pathname.startsWith('/comparisons')
  const section = analyses ? 'Analysis history' : 'API registry'
  const sectionPath = analyses ? '/comparisons' : '/catalog'
  const detail = pathname.startsWith(sectionPath + '/')
  return <div className="workspace">
    <a className="skip" href="#workspace-content">Skip to content</a>
    <aside className="sidebar">
      <Link to="/catalog" className="home-link"><Brand /></Link>
      <nav aria-label="Workspace">
        <NavLink to="/catalog"><span className="nav-icon catalog-icon" aria-hidden="true" />API registry</NavLink>
        <NavLink to="/comparisons"><span className="nav-icon compare-icon" aria-hidden="true" />Analysis history</NavLink>
      </nav>
      <div className="sidebar-footer">
        <div className="profile">
          <div className="avatar" aria-hidden="true">{user?.displayName.slice(0, 1).toUpperCase()}</div>
          <div><strong>{user?.displayName}</strong><small>{user?.roles.map(role => role.replace('_', ' ').toLowerCase()).join(', ')}</small></div>
        </div>
        <button className="button secondary logout" type="button" onClick={() => logout.mutate()} disabled={logout.isPending}>
          {logout.isPending ? 'Signing out…' : 'Sign out'}
        </button>
        <Feedback error={logout.error} />
      </div>
    </aside>
    <div className="workspace-main">
      <header className="workspace-topbar">
        <nav aria-label="Breadcrumb" className="breadcrumb">
          <span>Workspace</span>
          <span className="breadcrumb-divider" aria-hidden="true">/</span>
          {detail ? <>
            <Link to={sectionPath}>{section}</Link>
            <span className="breadcrumb-divider" aria-hidden="true">/</span>
            <span aria-current="page">{analyses ? 'Comparison' : 'API details'}</span>
          </> : <span aria-current="page">{section}</span>}
        </nav>
        <ThemeSwitch />
      </header>
      <main id="workspace-content" className="content" tabIndex={-1}><Outlet /></main>
    </div>
  </div>
}

export const developerARoutes: RouteObject[] = [
  { path: '/sign-in', element: <SignIn /> },
  { path: '/sign-up', element: <SignUp /> },
  {
    element: <ProtectedRoute />,
    children: [{
      element: <DeveloperAWorkspace />,
      children: [
        { path: '/', element: <Navigate to="/catalog" replace /> },
        { path: '/catalog', element: <ApiCatalog /> },
        { path: '/catalog/:apiId', element: <ApiDetail /> },
        { path: '/comparisons', element: <AnalysisHistory /> },
        { path: '/comparisons/:analysisId', element: <AnalysisDetail /> },
      ],
    }],
  },
  { path: '*', element: <Navigate to="/catalog" replace /> },
]
