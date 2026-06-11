import { session } from '../auth/session'
import type { TokenResponse } from './types'

const BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080'

export class ApiError extends Error {
  status: number
  body: unknown
  constructor(status: number, message: string, body: unknown) {
    super(message)
    this.status = status
    this.body = body
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  // internal: prevents infinite refresh recursion
  _retry?: boolean
}

// Single in-flight refresh shared by concurrent 401s.
let refreshing: Promise<boolean> | null = null

async function doRefresh(): Promise<boolean> {
  const refreshToken = session.getRefreshToken()
  if (!refreshToken) return false
  try {
    const res = await fetch(`${BASE}/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!res.ok) {
      session.clear()
      return false
    }
    const data = (await res.json()) as TokenResponse
    session.setTokens(data.accessToken, data.refreshToken)
    return true
  } catch {
    session.clear()
    return false
  }
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  const token = session.getAccessToken()
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'

  const res = await fetch(`${BASE}${path}`, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  // 401 → refresh once, then retry the original request.
  if (res.status === 401 && !opts._retry && session.getRefreshToken()) {
    refreshing = refreshing ?? doRefresh()
    const ok = await refreshing
    refreshing = null
    if (ok) return request<T>(path, { ...opts, _retry: true })
  }

  if (!res.ok) {
    let body: unknown = null
    try {
      body = await res.json()
    } catch {
      /* no body */
    }
    let msg = `${res.status} ${res.statusText}`
    if (body && typeof body === 'object' && 'message' in body) {
      msg = String((body as { message: unknown }).message)
    }
    throw new ApiError(res.status, msg, body)
  }

  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
}

export const apiBase = BASE
