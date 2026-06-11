import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import { session } from './session'
import type { MeResponse, Membership, TokenResponse, User } from '../api/types'

interface AuthState {
  user: User | null
  memberships: Membership[]
  activeOrgId: string | null
  loading: boolean
  setActiveOrg: (orgId: string) => void
  login: (email: string, password: string) => Promise<void>
  register: (email: string, displayName: string, password: string) => Promise<void>
  logout: () => void
  refreshMe: () => Promise<void>
}

const AuthCtx = createContext<AuthState | null>(null)

const ACTIVE_ORG_KEY = 'heimcall.activeOrgId'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [memberships, setMemberships] = useState<Membership[]>([])
  const [activeOrgId, setActiveOrgId] = useState<string | null>(localStorage.getItem(ACTIVE_ORG_KEY))
  const [loading, setLoading] = useState(true)

  function applyMe(me: MeResponse) {
    setUser(me.user)
    setMemberships(me.memberships)
    // Default the active org to a stored one if still valid, else the first membership.
    const stored = localStorage.getItem(ACTIVE_ORG_KEY)
    const valid = me.memberships.find((m) => m.organizationId === stored)
    const next = valid?.organizationId ?? me.memberships[0]?.organizationId ?? null
    setActiveOrgId(next)
    if (next) localStorage.setItem(ACTIVE_ORG_KEY, next)
  }

  async function refreshMe() {
    const me = await api.get<MeResponse>('/v1/auth/me')
    applyMe(me)
  }

  // On load: if a refresh token exists, the client's 401-refresh path will mint an access token
  // on the first /me call. If there's no refresh token (or it's expired), stay logged out.
  useEffect(() => {
    let cancelled = false
    async function bootstrap() {
      if (!session.getRefreshToken()) {
        setLoading(false)
        return
      }
      try {
        const me = await api.get<MeResponse>('/v1/auth/me')
        if (!cancelled) applyMe(me)
      } catch {
        session.clear()
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void bootstrap()
    return () => {
      cancelled = true
    }
  }, [])

  function setSessionFromTokens(t: TokenResponse) {
    session.setTokens(t.accessToken, t.refreshToken)
  }

  async function login(email: string, password: string) {
    const t = await api.post<TokenResponse>('/v1/auth/login', { email, password })
    setSessionFromTokens(t)
    await refreshMe()
  }

  async function register(email: string, displayName: string, password: string) {
    const t = await api.post<TokenResponse>('/v1/auth/register', { email, displayName, password })
    setSessionFromTokens(t)
    await refreshMe()
  }

  function logout() {
    session.clear()
    localStorage.removeItem(ACTIVE_ORG_KEY)
    setUser(null)
    setMemberships([])
    setActiveOrgId(null)
  }

  function setActiveOrg(orgId: string) {
    setActiveOrgId(orgId)
    localStorage.setItem(ACTIVE_ORG_KEY, orgId)
  }

  return (
    <AuthCtx.Provider
      value={{ user, memberships, activeOrgId, loading, setActiveOrg, login, register, logout, refreshMe }}
    >
      {children}
    </AuthCtx.Provider>
  )
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthCtx)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
