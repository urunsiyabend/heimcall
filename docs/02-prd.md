# Product Requirements Document - Incident Management / On-call Platform

Version: 0.1
Product codename: Watchtower
Owner: Siyabend Ürün

## 1. Summary

Watchtower is an incident management and on-call platform for engineering teams. It receives alerts from external monitoring systems, deduplicates noisy signals, creates incidents, routes them to the correct service/team, notifies on-call responders, escalates if no one acknowledges, and records a complete timeline for operational learning.

The initial product is a mini PagerDuty / Opsgenie / Splunk On-Call style system built with Spring Cloud, Kafka, Redis, PostgreSQL, and Kubernetes.

## 2. Problem Statement

Engineering teams running production systems need a reliable way to answer these questions:

- What broke?
- Which service is affected?
- Who owns this service?
- Who is on call right now?
- Was the responder notified?
- Did anyone acknowledge the incident?
- Should the incident escalate?
- When was it resolved?
- What happened during the incident?

Without an incident management platform, alerts become scattered across Grafana, Prometheus, Slack, email, logs, and human memory. The result is alert fatigue, missed incidents, unclear ownership, and weak post-incident learning.

## 3. Goals

## 3.1 Business goals

- Provide a practical incident management workflow for small and medium engineering teams.
- Reduce missed alerts by using escalation policies and on-call schedules.
- Reduce alert noise by using deduplication.
- Create an auditable incident timeline.
- Provide a learning-grade architecture using modern cloud-native technologies.

## 3.2 Product goals

- Accept alert events from external systems through webhook integrations.
- Normalize external payloads into internal alert events.
- Create incidents for actionable alerts.
- Deduplicate repeated alerts using dedup keys or external entity IDs.
- Route incidents to services, teams, schedules, and escalation policies.
- Notify responders via email, Telegram, and webhook in the MVP.
- Allow responders to acknowledge and resolve incidents.
- Escalate incidents if they are not acknowledged.
- Show incident list, detail, and timeline in a UI.

## 3.3 Engineering goals

- Use DDD-friendly bounded contexts.
- Use Spring Cloud for gateway, configuration, messaging integration, and resilience patterns.
- Use Kafka for domain events.
- Use Redis for cache, rate limiting, idempotency, and coordination.
- Use PostgreSQL as source of truth.
- Use Kubernetes for deployment.
- Provide high testability using Testcontainers and contract tests.

## 4. Non-goals for MVP

The MVP will not include:

- Native mobile apps.
- Phone-call based alerting.
- Advanced postmortem editor.
- AI-based incident summarization.
- Advanced SLO/error budget management.
- Complex holiday calendar integrations.
- Full Terraform provider.
- Enterprise SSO/SAML.
- Multi-region active-active deployment.

## 5. Target Users

## 5.1 Engineering manager

Needs:

- See open incidents.
- Understand team load.
- Know whether incidents are acknowledged.
- Review timelines after incidents.

## 5.2 On-call backend engineer

Needs:

- Receive urgent notifications.
- ACK an incident quickly.
- See incident details and history.
- Resolve incident when fixed.
- Avoid duplicate noise.

## 5.3 Platform engineer

Needs:

- Configure services, schedules, integrations, and escalation policies.
- Integrate Grafana, Prometheus Alertmanager, CloudWatch, or custom systems.
- Monitor the incident platform itself.

## 6. Personas

## Persona 1: Responder

Siyabend is on call for the Backend Team. At 03:00, Payment API starts returning 500 errors. Watchtower receives a CRITICAL event, creates an incident, sends Siyabend a Telegram notification, and waits for ACK. Siyabend clicks ACK, investigates the incident, fixes the issue, and resolves it. The timeline shows every action.

## Persona 2: Team Admin

Ayşe manages the Backend Team. She creates a weekly schedule, adds three engineers as participants, defines a critical escalation policy, and connects Payment API to that policy.

## Persona 3: Integrator

Ali owns monitoring. He creates a Grafana webhook integration, copies the generated integration key and routing key, and sends a test alert. Watchtower creates a test incident successfully.

## 7. Product Scope

## 7.1 MVP Scope

Core features:

1. Organization and user management.
2. Team management.
3. Monitored service management.
4. Integration key and routing key management.
5. Generic webhook ingestion.
6. Alert normalization.
7. Alert deduplication.
8. Incident lifecycle: triggered, acknowledged, resolved, canceled.
9. Incident timeline.
10. Basic on-call schedules with daily and weekly rotations.
11. Escalation policies with ordered rules.
12. Notification via email, Telegram, and webhook.
13. Incident list and detail UI.
14. Basic audit logging.

## 7.2 Version 1.1 Scope

1. Schedule overrides.
2. Maintenance windows.
3. Notification preferences.
4. Slack/Discord integration.
5. Incident reassignment.
6. Manual escalation.
7. Metrics dashboard.
8. Basic reporting: MTTA and MTTR.

## 7.3 Version 1.2 Scope

1. Postmortem templates.
2. Heartbeat monitoring.
3. Alert grouping rules.
4. Service dependency map.
5. Incident severity changes.
6. Escalation repeat policies.
7. Terraform/OpenTofu provider skeleton.

## 8. User Stories

## 8.1 Integration and alert ingestion

As a platform engineer, I want to create an integration key so that external monitoring systems can send alerts securely.

As a platform engineer, I want to configure routing keys so that alerts are routed to the correct service or team.

As an external monitoring system, I want to send CRITICAL, WARNING, INFO, ACKNOWLEDGEMENT, and RECOVERY events so that the incident platform can update lifecycle state.

## 8.2 Alert and incident lifecycle

As a responder, I want actionable alerts to create incidents so that I can track operational problems.

As a responder, I want duplicate alerts to update the existing incident so that I am not spammed by repeated incidents.

As a responder, I want to ACK an incident so that the system knows someone is handling it.

As a responder, I want to resolve an incident so that the incident no longer escalates.

As a team admin, I want every lifecycle action to be added to the timeline so that we can review the incident later.

## 8.3 Schedules and escalation

As a team admin, I want to create an on-call schedule so that Watchtower knows who is responsible at a given time.

As a team admin, I want to create an escalation policy so that incidents go to another responder if the first responder does not acknowledge.

As a responder, I want escalation to stop after I acknowledge so that teammates are not unnecessarily disturbed.

## 8.4 Notifications

As a responder, I want to receive notifications on my configured channel so that I can react quickly.

As a platform engineer, I want notification failures to be retried so that transient provider errors do not drop alerts.

As an admin, I want delivery logs so that I can verify whether a notification was sent.

## 9. Functional Requirements

## FR-001 Organization management

The system shall support organizations as top-level tenant boundaries.

## FR-002 User management

The system shall support users with profile, email, phone, and notification contact data.

## FR-003 Team management

The system shall support teams and team membership.

## FR-004 Service management

The system shall support monitored services and assign each service to an organization and optionally a team.

## FR-005 Escalation policy assignment

The system shall allow a service to be assigned to one escalation policy.

## FR-006 Integration creation

The system shall allow an admin to create an integration with generated API/integration keys.

## FR-007 Routing key support

The system shall support routing keys for mapping external events to services, teams, or policies.

## FR-008 Generic webhook ingestion

The system shall expose a generic webhook endpoint for external alerts.

## FR-009 Payload normalization

The system shall normalize provider payloads into an internal AlertReceivedEvent.

## FR-010 Message type handling

The system shall support message types:

- CRITICAL
- WARNING
- INFO
- ACKNOWLEDGEMENT
- RECOVERY

## FR-011 Incident creation

The system shall create an incident for actionable message types when no matching open incident exists.

## FR-012 Deduplication

The system shall deduplicate incoming events by organization, source, and dedup key/external entity id.

## FR-013 Incident acknowledgment

The system shall allow authorized users to acknowledge a triggered incident.

## FR-014 Incident resolution

The system shall allow authorized users to resolve an acknowledged or triggered incident.

## FR-015 Incident cancellation

The system shall allow authorized users to cancel invalid incidents.

## FR-016 Timeline

The system shall create timeline events for trigger, duplicate event, notification, escalation, ACK, resolve, cancel, and system errors.

## FR-017 Schedule creation

The system shall support daily and weekly on-call rotations.

## FR-018 Current on-call lookup

The system shall calculate the current on-call user for a schedule at a given time.

## FR-019 Escalation policy execution

The system shall execute escalation rules in order.

## FR-020 Escalation state check

The system shall check incident status before executing an escalation step.

## FR-021 Notification request

The system shall request notifications based on escalation targets.

## FR-022 Notification delivery tracking

The system shall store notification delivery attempts and outcomes.

## FR-023 Retry and dead-lettering

The system shall retry transient notification failures and dead-letter exhausted failures.

## FR-024 Incident UI

The UI shall show open incidents, incident detail, and timeline.

## FR-025 Lifecycle actions from UI

The UI shall allow ACK and resolve actions.

## 10. Non-functional Requirements

## NFR-001 Availability

The MVP target is 99.5% availability in a single-region deployment.

## NFR-002 Latency

For a valid incoming alert, the system should create or update an incident within 2 seconds under normal load.

## NFR-003 Notification latency

For CRITICAL incidents, the first notification should be requested within 5 seconds under normal load.

## NFR-004 Scalability

The system should scale stateless services horizontally in Kubernetes.

## NFR-005 Durability

Incident, alert, timeline, schedule, escalation, and notification records must be persisted in PostgreSQL.

## NFR-006 Event durability

Domain events must be published to Kafka with retry-safe producers and consumers.

## NFR-007 Security

Tenant data must be isolated by organization id.

## NFR-008 Observability

Every request and event processing path must include correlation id logging.

## NFR-009 Auditability

Privileged user actions must be auditable.

## NFR-010 Idempotency

Inbound alert events and lifecycle commands must support idempotency.

## 11. Data Model Summary

## 11.1 Identity

- Organization
- User
- Team
- TeamMember
- ApiKey

## 11.2 Service catalog

- MonitoredService
- ServiceTag
- RoutingKey

## 11.3 Alert and incident

- Alert
- Incident
- IncidentTimelineEvent
- IncidentAssignment

## 11.4 Scheduling

- OnCallSchedule
- ScheduleRotation
- RotationParticipant
- ScheduleOverride

## 11.5 Escalation

- EscalationPolicy
- EscalationRule
- EscalationTask

## 11.6 Notification

- ContactMethod
- NotificationRequest
- NotificationDelivery

## 12. Public API Draft

## 12.1 Integration endpoint

```http
POST /v1/integrations/{integrationKey}/events/{routingKey}
```

Request:

```json
{
  "messageType": "CRITICAL",
  "entityId": "payment-api-5xx-rate",
  "entityDisplayName": "Payment API 5xx rate high",
  "stateMessage": "Error rate exceeded 5% for 5 minutes",
  "service": "payment-api",
  "severity": "CRITICAL",
  "source": "grafana",
  "metadata": {
    "env": "production"
  }
}
```

## 12.2 Incident actions

```http
POST /v1/incidents/{incidentId}/acknowledge
POST /v1/incidents/{incidentId}/resolve
POST /v1/incidents/{incidentId}/cancel
```

## 12.3 Incident queries

```http
GET /v1/incidents?status=TRIGGERED
GET /v1/incidents/{incidentId}
GET /v1/incidents/{incidentId}/timeline
```

## 13. Lifecycle States

## 13.1 IncidentStatus

```text
TRIGGERED
ACKNOWLEDGED
RESOLVED
CANCELED
```

## 13.2 AlertStatus

```text
OPEN
ACKNOWLEDGED
CLOSED
SUPPRESSED
```

## 14. Key Product Rules

1. Every incident belongs to one organization.
2. Every incident belongs to one monitored service.
3. Every monitored service can have one active escalation policy.
4. Duplicate events with the same dedup key update the current open incident.
5. ACK means someone is taking responsibility, not that the problem is fixed.
6. Resolve means the problem is fixed or no longer active.
7. Escalation stops when an incident is acknowledged or resolved.
8. Recovery events resolve matching open incidents.
9. INFO events do not create incidents by default.
10. Every meaningful action creates a timeline event.

## 15. Success Metrics

Product metrics:

- Number of incidents created per day.
- Duplicate alert reduction ratio.
- Mean time to acknowledge.
- Mean time to resolve.
- Escalation rate.
- Notification delivery success rate.

Engineering metrics:

- Kafka consumer lag.
- API p95 latency.
- Notification provider failure rate.
- Incident creation error rate.
- Dead-letter topic message count.

## 16. Launch Criteria

MVP can be considered launchable when:

- Generic webhook integration works.
- Incident lifecycle works.
- Deduplication works.
- Schedule lookup works.
- Escalation policy with at least two levels works.
- Email or Telegram notification works.
- ACK cancels pending escalation.
- Timeline is complete.
- Kubernetes deployment manifests exist.
- End-to-end tests pass.

## 17. Open Questions

1. Should WARNING create incidents by default, or only alerts?
2. Should resolved incidents be reopened by a new CRITICAL event with same dedup key?
3. Should escalation notify one target at a time or all targets in a level?
4. Should team target notify all team members or only team on-call schedule?
5. Should maintenance window suppress incident creation or only notifications?
6. Should ACK from external event be allowed without a user identity?
7. Should unacknowledge be supported in the first version?

## 18. Source Notes

This PRD uses public product concepts from PagerDuty, Opsgenie, and Splunk On-Call / VictorOps: services, schedules, escalation policies, alert notification flow, deduplication, routing keys, entity identifiers, and trigger/acknowledge/recovery lifecycle actions.
