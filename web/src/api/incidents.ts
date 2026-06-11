import { api } from './client'
import type { Alert, AlertOccurrence, Incident, IncidentStatus, TimelineEvent } from './types'

// GET /v1/incidents?organizationId=&status= — member-gated, org-filtered (tenant isolation).
export function listIncidents(organizationId: string, status?: IncidentStatus): Promise<Incident[]> {
  const params = new URLSearchParams({ organizationId })
  if (status) params.set('status', status)
  return api.get<Incident[]>(`/v1/incidents?${params.toString()}`)
}

// Per-incident reads derive the org from the incident and enforce caller membership (404/403).
export const getIncident = (id: string) => api.get<Incident>(`/v1/incidents/${id}`)
export const getTimeline = (id: string) => api.get<TimelineEvent[]>(`/v1/incidents/${id}/timeline`)
export const getAlerts = (id: string) => api.get<Alert[]>(`/v1/incidents/${id}/alerts`)
export const getOccurrences = (id: string, alertId: string) =>
  api.get<AlertOccurrence[]>(`/v1/incidents/${id}/alerts/${alertId}/occurrences`)
