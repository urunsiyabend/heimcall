import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import type { Organization } from '../api/types'

// Minimal create-org flow (decision D2): create the org, then bootstrap the current user as OWNER.
// The first membership of an org needs no existing member to authorize it (backend bootstrap rule).
function slugify(name: string): string {
  return name
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

export function CreateOrgPage() {
  const { user, setActiveOrg, refreshMe } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!user) return
    setError(null)
    setBusy(true)
    try {
      const org = await api.post<Organization>('/v1/organizations', { name, slug: slugify(name) })
      await api.post(`/v1/organizations/${org.id}/memberships`, { userId: user.id, role: 'OWNER' })
      await refreshMe()
      setActiveOrg(org.id)
      navigate('/')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create organization')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <h2>New organization</h2>
      <form onSubmit={onSubmit}>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        {name && <p className="hint">slug: {slugify(name) || '—'}</p>}
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={busy || !slugify(name)}>
          {busy ? 'Creating…' : 'Create'}
        </button>
      </form>
    </div>
  )
}
