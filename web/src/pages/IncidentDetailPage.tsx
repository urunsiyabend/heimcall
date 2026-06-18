import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getAlerts, getIncident, getTimeline } from '../api/incidents'
import { ApiError } from '../api/client'
import { SeverityBadge, StatusBadge } from '../components/StatusBadge'
import { AlertOccurrences } from '../components/AlertOccurrences'
import { IncidentActions } from '../components/IncidentActions'
import type { Alert, Incident, TimelineEvent } from '../api/types'

function fmt(ts: string): string {
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? ts : d.toLocaleString()
}

export function IncidentDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [incident, setIncident] = useState<Incident | null>(null)
  const [timeline, setTimeline] = useState<TimelineEvent[]>([])
  const [alerts, setAlerts] = useState<Alert[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!id) return
    setLoading(true)
    setError(null)
    try {
      const [inc, tl, al] = await Promise.all([getIncident(id), getTimeline(id), getAlerts(id)])
      setIncident(inc)
      setTimeline(tl)
      setAlerts(al)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load incident')
    } finally {
      setLoading(false)
    }
  }, [id])

  useEffect(() => {
    void load()
  }, [load])

  if (loading) return <p className="hint">Loading…</p>
  if (error) return <p className="error">{error}</p>
  if (!incident) return <p className="hint">Not found.</p>

  return (
    <div className="detail">
      <Link to="/" className="btn-link">
        ← Incidents
      </Link>

      <div className="detail-head">
        <h2>{incident.title}</h2>
        <div className="badges">
          <SeverityBadge severity={incident.severity} />
          <StatusBadge status={incident.status} />
          {incident.unrouted && (
            <span className="badge badge-unrouted" title="No routing match and no org-default escalation policy — nobody was paged">
              UNROUTED
            </span>
          )}
        </div>
      </div>

      <IncidentActions incidentId={incident.id} status={incident.status} onChanged={load} />

      <dl className="meta-grid">
        <dt>Source</dt>
        <dd>{incident.source}</dd>
        <dt>Routing key</dt>
        <dd>{incident.routingKey ?? '—'}</dd>
        <dt>Service</dt>
        <dd>{incident.serviceId ?? '—'}</dd>
        <dt>Created</dt>
        <dd>{fmt(incident.createdAt)}</dd>
        <dt>Last event</dt>
        <dd>{fmt(incident.lastEventAt)}</dd>
      </dl>
      {incident.description && <p>{incident.description}</p>}

      <section>
        <h3>Timeline</h3>
        {timeline.length === 0 ? (
          <p className="hint">No timeline events.</p>
        ) : (
          <ul className="timeline">
            {timeline.map((e) => (
              <li key={e.id}>
                <span className="badge">{e.type}</span>
                <span>{e.message ?? ''}</span>
                <span className="hint">{fmt(e.createdAt)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h3>Alerts</h3>
        {alerts.length === 0 ? (
          <p className="hint">No alerts.</p>
        ) : (
          alerts.map((a) => (
            <div key={a.id} className="alert-card">
              <div className="alert-head">
                <span>{a.title}</span>
                <SeverityBadge severity={a.severity} />
                <span className="badge">{a.status}</span>
              </div>
              <p className="hint">
                {a.externalEntityId} · first {fmt(a.firstSeenAt)} · last {fmt(a.lastSeenAt)}
              </p>
              <AlertOccurrences incidentId={incident.id} alertId={a.id} count={a.occurrenceCount} />
            </div>
          ))
        )}
      </section>
    </div>
  )
}
