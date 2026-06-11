import { useEffect, useRef } from 'react'
import { apiBase } from '../api/client'
import { session } from '../auth/session'

interface StreamHandlers {
  // Called on (re)connect — the stream has no replay/Last-Event-ID, so the consumer MUST refetch the
  // full list to close any gap that opened while disconnected.
  onConnect: () => void
  // Called per lifecycle event pushed by the server ({incidentId, status, at}).
  onEvent: (e: { incidentId: string; status: string; at: string }) => void
}

// Opens an EventSource to GET /v1/incidents/stream for the active org. EventSource cannot set headers,
// so the access token rides in the access_token query param (the JWT filter honors it only on this path).
// Reconnects when organizationId changes. Token-refresh-driven reconnect is a known MVP gap: if the
// access token expires mid-stream the browser keeps retrying with the stale token until a manual reload.
export function useIncidentStream(organizationId: string | null, handlers: StreamHandlers) {
  // Keep latest handlers in a ref so re-renders don't tear down the connection.
  const hRef = useRef(handlers)
  hRef.current = handlers

  useEffect(() => {
    const token = session.getAccessToken()
    if (!organizationId || !token) return

    const url = `${apiBase}/v1/incidents/stream?organizationId=${encodeURIComponent(
      organizationId,
    )}&access_token=${encodeURIComponent(token)}`
    const es = new EventSource(url)

    es.onopen = () => hRef.current.onConnect()
    es.addEventListener('incident', (ev) => {
      try {
        hRef.current.onEvent(JSON.parse((ev as MessageEvent).data))
      } catch {
        /* ignore malformed frame */
      }
    })
    // On error EventSource auto-reconnects; nothing to do beyond letting it retry.

    return () => es.close()
  }, [organizationId])
}
