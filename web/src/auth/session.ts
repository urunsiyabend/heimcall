// Decision D1 (MVP): access token in memory only; refresh token in localStorage for silent-refresh
// across reloads. The refresh token is XSS-readable here — acceptable for a local-dev MVP, NOT for
// production. The proper fix (httpOnly Set-Cookie) is a deferred backend sub-ticket.

const REFRESH_KEY = 'heimcall.refreshToken'

let accessToken: string | null = null

export const session = {
  getAccessToken: () => accessToken,
  setAccessToken: (t: string | null) => {
    accessToken = t
  },
  getRefreshToken: () => localStorage.getItem(REFRESH_KEY),
  setRefreshToken: (t: string | null) => {
    if (t) localStorage.setItem(REFRESH_KEY, t)
    else localStorage.removeItem(REFRESH_KEY)
  },
  setTokens: (access: string | null, refresh: string | null) => {
    session.setAccessToken(access)
    session.setRefreshToken(refresh)
  },
  clear: () => {
    accessToken = null
    localStorage.removeItem(REFRESH_KEY)
  },
}
