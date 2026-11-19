import { createContext, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'

export type Role = 'ADMIN' | 'API_OWNER' | 'CONSUMER'
export type User = { id: string; email: string; displayName: string; roles: Role[] }
export type Page<T> = { items: T[]; page: number; size: number; hasNext: boolean }
type Session = { accessToken: string; expiresIn: number; user: User }
type SessionState =
  | { status: 'checking' | 'anonymous' | 'unavailable'; user?: undefined }
  | { status: 'signed-in'; user: User }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string,
              readonly fields: { field: string; message: string }[] = [],
              readonly retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

const csrf = { 'X-Sentinel-CSRF': '1' }
export function jsonBody(value: unknown): RequestInit {
  return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }
}

class SessionClient {
  private token?: string
  private state: SessionState = { status: 'checking' }
  private listeners = new Set<() => void>()
  private renewal?: Promise<void>

  constructor(private base: string, private clearQueries: () => void) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  snapshot = () => this.state

  restore = async () => {
    try {
      await this.refresh()
    } catch (error) {
      if (this.state.status === 'checking' || this.state.status === 'unavailable') {
        this.setState({ status: error instanceof ApiError && error.status === 401 ? 'anonymous' : 'unavailable' })
      }
    }
  }

  async register(fields: { email: string; password: string; displayName: string; role: Role }) {
    return this.decode<User>(await this.fetch('/auth/register', { method: 'POST', ...jsonBody(fields) }))
  }

  async login(email: string, password: string) {
    await this.renewal?.catch(() => {})
    const body = jsonBody({ email, password })
    const response = await this.fetch('/auth/login', {
      ...body, method: 'POST', headers: { 'Content-Type': 'application/json', ...csrf },
    })
    this.setSession(await this.decode<Session>(response))
  }

  async logout() {
    await this.renewal?.catch(() => {})
    const response = await this.fetch('/auth/logout', { method: 'POST', headers: csrf })
    if (!response.ok) await this.problem(response)
    this.endSession()
  }

  async json<T>(path: string, options: RequestInit = {}) {
    return this.decode<T>(await this.request(path, options))
  }

  async download(path: string, filename: string) {
    const response = await this.request(path, {})
    if (!response.ok) await this.problem(response)
    const url = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.append(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  private async request(path: string, options: RequestInit) {
    const sentToken = this.token
    const send = () => {
      const headers = new Headers(options.headers)
      if (this.token) headers.set('Authorization', 'Bearer ' + this.token)
      return this.fetch(path, { ...options, headers })
    }
    const response = await send()
    if (response.status !== 401 || !sentToken) return response
    if (sentToken === this.token) await this.refresh()
    if (!this.token) return response
    return send()
  }

  private refresh() {
    if (!this.renewal) {
      this.renewal = this.fetch('/auth/refresh', { method: 'POST', headers: csrf })
        .then(response => this.decode<Session>(response))
        .then(session => this.setSession(session))
        .catch(error => {
          if (error instanceof ApiError && error.status === 401) this.endSession()
          throw error
        })
        .finally(() => { this.renewal = undefined })
    }
    return this.renewal
  }

  private setSession(session: Session) {
    if (this.state.user?.id !== session.user.id) this.clearQueries()
    this.token = session.accessToken
    this.setState({ status: 'signed-in', user: session.user })
  }

  private endSession() {
    if (this.state.user) this.clearQueries()
    this.token = undefined
    this.setState({ status: 'anonymous' })
  }

  private setState(state: SessionState) {
    this.state = state
    this.listeners.forEach(listener => listener())
  }

  private async fetch(path: string, request: RequestInit) {
    try {
      return await fetch(this.base + path, { ...request, credentials: 'include' })
    } catch (error) {
      if (request.signal?.aborted) throw error
      throw new ApiError(0, 'NETWORK_ERROR', 'The service could not be reached. Try again.')
    }
  }

  private async decode<T>(response: Response): Promise<T> {
    if (!response.ok) await this.problem(response)
    return response.json() as Promise<T>
  }

  private async problem(response: Response): Promise<never> {
    const value = await response.json().catch(() => ({}))
    const detail = typeof value?.detail === 'string' ? value.detail : 'The service returned HTTP ' + response.status + '.'
    const retry = value?.retryAfterSeconds
    const retryAfterSeconds = Number.isInteger(retry) && retry > 0 && retry <= 3600 ? retry : undefined
    throw new ApiError(response.status, value?.code ?? 'HTTP_ERROR', detail,
      Array.isArray(value?.fields) ? value.fields : [], retryAfterSeconds)
  }
}

const Context = createContext<SessionClient | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const query = useQueryClient()
  const client = useMemo(() => new SessionClient((import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, ''), () => query.clear()), [query])
  useEffect(() => { void client.restore() }, [client])
  return <Context.Provider value={client}>{children}</Context.Provider>
}

export function useSession() {
  const client = useContext(Context)
  if (!client) throw new Error('SessionProvider is required')
  const session = useSyncExternalStore(client.subscribe, client.snapshot)
  return { client, session }
}

export function canWrite(user?: User) {
  return user?.roles.some(role => role === 'API_OWNER' || role === 'ADMIN') ?? false
}
