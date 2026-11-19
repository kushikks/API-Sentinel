import { useId, useState, type FormEvent, type InputHTMLAttributes, type ReactNode } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { useSession, type Role } from './session'
import { Brand, Feedback, Pending } from './ui'
import { AuthArtwork } from './AuthArtwork'
import { ThemeSwitch } from './theme'

export function SignIn() {
  const { client, session } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const target = typeof location.state?.from === 'string' && location.state.from.startsWith('/') && !location.state.from.startsWith('//')
    ? location.state.from : '/catalog'
  const login = useMutation({ mutationFn: () => client.login(email, password), onSuccess: () => navigate(target, { replace: true }) })
  if (session.status === 'checking') return <Pending>Checking your session…</Pending>
  if (session.status === 'signed-in') return <Navigate to={target} replace />
  return <AuthPanel title="Sign in" detail="Use your workspace account to continue.">
    {location.state?.created && <div role="status" className="feedback">Account created. Sign in to continue.</div>}
    {session.status === 'unavailable' && <div className="feedback error">Identity could not check your session.
      <button type="button" className="text-button" onClick={() => void client.restore()}>Check again</button>
    </div>}
    <form className="form" onSubmit={(event: FormEvent) => { event.preventDefault(); login.mutate() }}>
      <label>Email<input type="email" name="email" placeholder="you@example.com" autoComplete="username" value={email}
        onChange={event => setEmail(event.target.value)} required maxLength={254} /></label>
      <PasswordInput autoComplete="current-password" value={password}
        onChange={event => setPassword(event.target.value)} required maxLength={72} />
      <Feedback error={login.error} />
      <button type="submit" className="button primary" disabled={login.isPending}>{login.isPending ? 'Signing in…' : 'Sign in'}</button>
    </form>
    <p className="auth-footer">New here? <Link to="/sign-up">Create an account</Link></p>
  </AuthPanel>
}

export function SignUp() {
  const { client, session } = useSession()
  const navigate = useNavigate()
  const [role, setRole] = useState<Role>('API_OWNER')
  const signup = useMutation({
    mutationFn: (fields: FormData) => client.register({
      email: String(fields.get('email')), password: String(fields.get('password')),
      displayName: String(fields.get('displayName')), role,
    }),
    onSuccess: () => navigate('/sign-in', { replace: true, state: { created: true } }),
  })
  if (session.status === 'signed-in') return <Navigate to="/catalog" replace />
  return <AuthPanel title="Create an account" detail="Choose how you’ll use the workspace.">
    <form className="form" onSubmit={event => { event.preventDefault(); signup.mutate(new FormData(event.currentTarget)) }}>
      <label>Display name<input name="displayName" autoComplete="name" required maxLength={100} /></label>
      <label>Email<input type="email" name="email" autoComplete="username" required maxLength={254} /></label>
      <PasswordInput autoComplete="new-password" required minLength={12} maxLength={72}
        hint="Use at least 12 characters and at most 72 UTF-8 bytes." />
      <label>Role<select value={role} onChange={event => setRole(event.target.value as Role)}>
        <option value="API_OWNER">API owner</option>
        <option value="CONSUMER">Consumer</option>
      </select></label>
      <Feedback error={signup.error} />
      <button type="submit" className="button primary" disabled={signup.isPending}>{signup.isPending ? 'Creating…' : 'Create account'}</button>
    </form>
    <p className="auth-footer">Already registered? <Link to="/sign-in">Sign in</Link></p>
  </AuthPanel>
}

function PasswordInput({ hint, ...input }: InputHTMLAttributes<HTMLInputElement> & { hint?: string }) {
  const id = useId()
  const [visible, setVisible] = useState(false)
  return <div className="password-field">
    <label htmlFor={id}>Password</label>
    <div className="password-control">
      <input {...input} id={id} type={visible ? 'text' : 'password'} name="password" placeholder="Enter your password"
        aria-describedby={hint ? id + '-hint' : undefined} />
      <button type="button" className="password-toggle" aria-controls={id} aria-label={visible ? 'Hide password' : 'Show password'}
        onClick={() => setVisible(current => !current)}>{visible ? 'Hide' : 'Show'}</button>
    </div>
    {hint && <small id={id + '-hint'}>{hint}</small>}
  </div>
}

function AuthPanel({ title, detail, children }: { title: string; detail: string; children: ReactNode }) {
  return <div className="auth-page">
    <header className="auth-header">
      <div className="auth-header-inner"><Brand /><ThemeSwitch /></div>
    </header>
    <main className="auth-layout">
      <div className="auth-intro">
        <h2>Catch breaking<br /><span>API changes.</span></h2>
        <p>Compare OpenAPI versions and review compatibility findings before they reach consumers.</p>
        <AuthArtwork />
      </div>
      <section className="auth-card" aria-labelledby="auth-title">
        <h1 id="auth-title">{title}</h1>
        <p className="muted">{detail}</p>
        {children}
      </section>
    </main>
  </div>
}
