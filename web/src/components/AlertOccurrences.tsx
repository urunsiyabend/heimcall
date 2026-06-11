import { useState } from 'react'
import { getOccurrences } from '../api/incidents'
import { ApiError } from '../api/client'
import type { AlertOccurrence } from '../api/types'

function fmt(ts: string | null): string {
  if (!ts) return '—'
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? ts : d.toLocaleString()
}

// Lazy-loads the occurrence history for one alert on first expand.
export function AlertOccurrences({ incidentId, alertId, count }: { incidentId: string; alertId: string; count: number }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState<AlertOccurrence[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function toggle() {
    const next = !open
    setOpen(next)
    if (next && rows === null) {
      try {
        setRows(await getOccurrences(incidentId, alertId))
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load occurrences')
      }
    }
  }

  return (
    <div className="occurrences">
      <button className="btn-link" onClick={toggle}>
        {open ? '▾' : '▸'} {count} occurrence{count === 1 ? '' : 's'}
      </button>
      {open && (
        <div className="occ-body">
          {error && <p className="error">{error}</p>}
          {rows?.length === 0 && <p className="hint">No occurrences.</p>}
          {rows?.map((o) => (
            <div key={o.id} className="occ-row">
              <span className="badge">{o.messageType}</span>
              <span>{o.title ?? '—'}</span>
              <span className="hint">{fmt(o.occurredAt ?? o.receivedAt)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
