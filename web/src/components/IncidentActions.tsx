import { useState } from 'react'
import { actOnIncident, type IncidentAction } from '../api/incidents'
import { ApiError } from '../api/client'
import type { IncidentStatus } from '../api/types'

// Which actions are offered per current status. RESOLVED/CANCELED are terminal → no actions.
const ACTIONS_BY_STATUS: Record<IncidentStatus, IncidentAction[]> = {
  TRIGGERED: ['acknowledge', 'resolve', 'cancel'],
  ACKNOWLEDGED: ['resolve', 'cancel'],
  RESOLVED: [],
  CANCELED: [],
}

const LABEL: Record<IncidentAction, string> = {
  acknowledge: 'Acknowledge',
  resolve: 'Resolve',
  cancel: 'Cancel',
}

// onChanged refetches the incident + timeline after a successful command.
export function IncidentActions({
  incidentId,
  status,
  onChanged,
}: {
  incidentId: string
  status: IncidentStatus
  onChanged: () => void | Promise<void>
}) {
  const [busy, setBusy] = useState<IncidentAction | null>(null)
  const [error, setError] = useState<string | null>(null)
  const actions = ACTIONS_BY_STATUS[status]

  async function run(action: IncidentAction) {
    setBusy(action)
    setError(null)
    try {
      await actOnIncident(incidentId, action)
      await onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : `Failed to ${action}`)
    } finally {
      setBusy(null)
    }
  }

  if (actions.length === 0) {
    return <span className="hint">No actions ({status.toLowerCase()}).</span>
  }

  return (
    <div className="actions">
      {actions.map((a) => (
        <button key={a} onClick={() => run(a)} disabled={busy !== null} className={a === 'cancel' ? 'danger' : ''}>
          {busy === a ? '…' : LABEL[a]}
        </button>
      ))}
      {error && <p className="error">{error}</p>}
    </div>
  )
}
