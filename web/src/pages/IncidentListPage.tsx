import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useIncidents } from '../hooks/useIncidents'
import { useIncidentStream } from '../hooks/useIncidentStream'
import { SeverityBadge, StatusBadge } from '../components/StatusBadge'
import type { IncidentStatus } from '../api/types'

const STATUSES: IncidentStatus[] = ['TRIGGERED', 'ACKNOWLEDGED', 'RESOLVED', 'CANCELED']

function fmt(ts: string): string {
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? ts : d.toLocaleString()
}

export function IncidentListPage() {
  const { memberships, activeOrgId } = useAuth()
  const [filter, setFilter] = useState<IncidentStatus | 'ALL'>('ALL')
  const { incidents, loading, error, reload } = useIncidents(
    activeOrgId,
    filter === 'ALL' ? undefined : filter,
  )

  // Live updates: refetch on (re)connect (no replay) and on every lifecycle event. Reloading rather than
  // patching in place keeps the status filter honest (a resolved incident leaves a TRIGGERED-filtered view).
  useIncidentStream(activeOrgId, { onConnect: reload, onEvent: reload })

  if (memberships.length === 0) {
    return (
      <div className="panel">
        <h2>No organizations yet</h2>
        <p>Create one to start receiving incidents.</p>
        <Link to="/orgs/new" className="btn-link">
          + New organization
        </Link>
      </div>
    )
  }

  return (
    <div>
      <div className="list-header">
        <h2>Incidents</h2>
        <div className="filters">
          <button className={filter === 'ALL' ? 'chip active' : 'chip'} onClick={() => setFilter('ALL')}>
            All
          </button>
          {STATUSES.map((s) => (
            <button key={s} className={filter === s ? 'chip active' : 'chip'} onClick={() => setFilter(s)}>
              {s}
            </button>
          ))}
          <button className="chip" onClick={reload} disabled={loading}>
            ↻
          </button>
        </div>
      </div>

      {error && <p className="error">{error}</p>}
      {loading && incidents.length === 0 && <p className="hint">Loading…</p>}
      {!loading && !error && incidents.length === 0 && (
        <p className="hint">No incidents{filter !== 'ALL' ? ` with status ${filter}` : ''}.</p>
      )}

      {incidents.length > 0 && (
        <table className="grid">
          <thead>
            <tr>
              <th>Title</th>
              <th>Severity</th>
              <th>Status</th>
              <th>Source</th>
              <th>Created</th>
              <th>Last event</th>
            </tr>
          </thead>
          <tbody>
            {incidents.map((i) => (
              <tr key={i.id}>
                <td>
                  <Link to={`/incidents/${i.id}`}>{i.title}</Link>
                </td>
                <td>
                  <SeverityBadge severity={i.severity} />
                </td>
                <td>
                  <StatusBadge status={i.status} />
                </td>
                <td>{i.source}</td>
                <td>{fmt(i.createdAt)}</td>
                <td>{fmt(i.lastEventAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
