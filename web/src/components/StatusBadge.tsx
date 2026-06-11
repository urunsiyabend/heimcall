import type { IncidentStatus, Severity } from '../api/types'

export function StatusBadge({ status }: { status: IncidentStatus }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{status}</span>
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  return <span className={`badge sev-${severity.toLowerCase()}`}>{severity}</span>
}
