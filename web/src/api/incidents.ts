import { api } from './client'
import type { Incident, IncidentStatus } from './types'

// GET /v1/incidents?organizationId=&status= — member-gated, org-filtered (tenant isolation).
export function listIncidents(organizationId: string, status?: IncidentStatus): Promise<Incident[]> {
  const params = new URLSearchParams({ organizationId })
  if (status) params.set('status', status)
  return api.get<Incident[]>(`/v1/incidents?${params.toString()}`)
}
