// Hand-written types mirroring the gateway contracts (verified against service source).

export interface User {
  id: string
  email: string
  displayName: string
}

export interface Membership {
  organizationId: string
  organizationName: string
  role: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  user: User
}

export interface MeResponse {
  user: User
  memberships: Membership[]
}

export interface Organization {
  id: string
  name: string
  slug: string
}

export type IncidentStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED' | 'CANCELED'
export type Severity = 'CRITICAL' | 'WARNING' | 'INFO'

export interface Incident {
  id: string
  organizationId: string
  source: string
  dedupKey: string
  title: string
  description: string | null
  severity: Severity
  status: IncidentStatus
  routingKey: string | null
  serviceId: string | null
  escalationPolicyId: string | null
  unrouted: boolean
  createdAt: string
  updatedAt: string
  lastEventAt: string
}

export interface TimelineEvent {
  id: string
  incidentId: string
  type: string
  message: string | null
  createdAt: string
}

export type AlertStatus = 'TRIGGERED' | 'ACKNOWLEDGED' | 'RESOLVED'

export interface Alert {
  id: string
  organizationId: string
  incidentId: string
  source: string
  dedupKey: string
  externalEntityId: string
  status: AlertStatus
  severity: Severity
  title: string
  occurrenceCount: number
  firstSeenAt: string
  lastSeenAt: string
}

export interface AlertOccurrence {
  id: string
  alertId: string
  eventId: string
  messageType: string
  severity: Severity
  title: string | null
  description: string | null
  occurredAt: string | null
  receivedAt: string
}
