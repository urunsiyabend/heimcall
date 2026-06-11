import { useCallback, useEffect, useState } from 'react'
import { listIncidents } from '../api/incidents'
import { ApiError } from '../api/client'
import type { Incident, IncidentStatus } from '../api/types'

interface State {
  incidents: Incident[]
  loading: boolean
  error: string | null
  reload: () => void
}

// Fetches the org's incidents (optionally status-filtered). `reload` is exposed so later slices
// (lifecycle actions, SSE) can force a refetch — the SSE layer refetches the full list on (re)connect.
export function useIncidents(organizationId: string | null, status?: IncidentStatus): State {
  const [incidents, setIncidents] = useState<Incident[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!organizationId) {
      setIncidents([])
      return
    }
    setLoading(true)
    setError(null)
    try {
      setIncidents(await listIncidents(organizationId, status))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load incidents')
    } finally {
      setLoading(false)
    }
  }, [organizationId, status])

  useEffect(() => {
    void load()
  }, [load])

  return { incidents, loading, error, reload: load }
}
