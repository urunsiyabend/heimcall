# Domain Glossary / Ubiquitous Language

Project: Incident Management / On-call Platform
Version: 0.1

## 1. Purpose

This document defines the shared language for the incident management platform. Product, backend, frontend, QA, DevOps, and documentation should use these terms consistently.

The system is inspired by incident management and on-call products such as PagerDuty, Opsgenie, and Splunk On-Call / VictorOps. The vocabulary below adapts those public concepts into our own product language.

## 2. Core Domain Statement

An external system sends an Event. The platform normalizes it into an Alert. If the alert is actionable, the platform creates or updates an Incident. The Incident is associated with a Service. The Service has an Escalation Policy. The Escalation Policy uses Schedules, Users, Teams, and rules to decide who receives Notifications. Responders can Acknowledge or Resolve the Incident. Every meaningful action becomes a Timeline Event.

## 3. Glossary

## Organization

A tenant boundary. All users, teams, services, schedules, integrations, incidents, and policies belong to one organization.

Rules:

- Data from one organization must never be visible to another organization.
- Every aggregate root must carry organization id.

Example:

```text
Organization: Acme Engineering
```

## User

A person who can log in, configure resources, receive notifications, or respond to incidents.

Example:

```text
Siyabend Ürün
```

## Team

A group of users responsible for one or more services.

Example:

```text
Backend Team
Platform Team
Database Team
```

Rules:

- A team belongs to one organization.
- A team may own multiple services.
- A team may have schedules and escalation policies.

## Team Member

A user belonging to a team with a role.

Example roles:

```text
TEAM_ADMIN
RESPONDER
VIEWER
```

## Service / Monitored Service

A system, application, component, or infrastructure resource that can be affected by incidents.

Examples:

```text
Payment API
Auth Service
Notification Worker
PostgreSQL Cluster
Redis Cache
Kafka Consumer Group
```

Rules:

- A service belongs to one organization.
- A service may be owned by a team.
- A service should have one active escalation policy.
- Incidents should be associated with a service.

## Integration

A configured entry point that allows an external system to send events into the platform.

Examples:

```text
Grafana Webhook Integration
Prometheus Alertmanager Integration
CloudWatch Integration
Generic Webhook Integration
```

Rules:

- Integrations are authenticated using integration keys or API keys.
- An integration belongs to one organization.
- Integration secrets must be stored hashed or encrypted.

## Integration Key

A secret token used by external systems to authenticate with an integration endpoint.

Rules:

- Must not be stored in plaintext.
- Must be rotatable.
- Must be revocable.
- Must be rate limited.

## Routing Key

A public-ish routing identifier used to route inbound events to a service, team, or escalation policy.

Example:

```text
backend-critical
payment-api
platform-warning
```

Rules:

- Routing key is not a secret.
- Routing key must resolve to a target.
- Invalid routing keys should be rejected or routed to a default fallback policy depending on configuration.

## Raw Event

The original payload received from an external system before normalization.

Rules:

- Store raw event for debugging and audit if payload size is acceptable.
- Raw event is not used directly by the core incident domain.
- Sensitive fields should be redacted where necessary.

## Event / External Event

A signal sent by an external system.

Examples:

```text
Grafana alert fired
Prometheus Alertmanager alert resolved
Custom app health check failed
Splunk On-Call style RECOVERY message
```

Important distinction:

- Event is external or transport-level.
- Alert is normalized internal signal.
- Incident is actionable operational problem.

## Message Type

A normalized classification that tells the platform how to process an event.

Allowed values:

```text
CRITICAL
WARNING
INFO
ACKNOWLEDGEMENT
RECOVERY
```

Meaning:

- CRITICAL: usually creates or updates an incident.
- WARNING: may create an incident depending on service policy.
- INFO: usually records a timeline event only.
- ACKNOWLEDGEMENT: acknowledges a matching incident.
- RECOVERY: resolves a matching incident.

## Alert

A normalized warning or signal inside the platform.

Rules:

- Alert may or may not create an incident.
- Alert has a source, message type, severity, dedup key, and status.
- Duplicate alert events may increment alert count.

Example:

```text
Alert: Payment API 5xx rate high
Source: Grafana
Message Type: CRITICAL
Dedup Key: grafana:payment-api-5xx-rate
```

## Actionable Alert

An alert that should create or update an incident.

Examples:

- CRITICAL alert from production service.
- WARNING alert configured to open incidents.

Non-examples:

- INFO message.
- Alert suppressed by maintenance window.

## Incident

An operational problem that requires human or automated response.

Rules:

- Incident belongs to one organization.
- Incident is usually associated with one service.
- Incident has lifecycle state.
- Incident has severity.
- Incident has timeline events.
- Incident may be assigned to a user or team.

Example:

```text
INC-1001 Payment API 5xx rate high
Status: TRIGGERED
Severity: CRITICAL
Service: Payment API
```

## Incident Status

The lifecycle state of an incident.

Allowed values:

```text
TRIGGERED
ACKNOWLEDGED
RESOLVED
CANCELED
```

Definitions:

- TRIGGERED: incident is open and not yet acknowledged.
- ACKNOWLEDGED: responder has taken responsibility.
- RESOLVED: problem is fixed or no longer active.
- CANCELED: incident is invalid or intentionally discarded.

## Alert Status

The lifecycle state of an alert.

Allowed values:

```text
OPEN
ACKNOWLEDGED
CLOSED
SUPPRESSED
```

Definitions:

- OPEN: alert is active.
- ACKNOWLEDGED: alert has been acknowledged.
- CLOSED: alert is no longer active.
- SUPPRESSED: alert was intentionally muted or not escalated.

## Severity

The business and operational seriousness of an alert or incident.

Allowed values for MVP:

```text
INFO
WARNING
ERROR
CRITICAL
```

Recommended interpretation:

- INFO: useful context, no immediate action.
- WARNING: potential problem.
- ERROR: active problem with limited impact.
- CRITICAL: urgent problem with major or production impact.

## Deduplication

The rule that prevents repeated events from creating repeated incidents.

Canonical key:

```text
organizationId + source + dedupKey
```

Alternative key:

```text
organizationId + integrationId + externalEntityId
```

Rules:

- If an open incident exists for the dedup key, update it instead of creating a new incident.
- Increase alert count or occurrence count.
- Add a timeline event for duplicate occurrence.

## Dedup Key

An internal key used to identify repeated alerts for the same problem.

Example:

```text
grafana:payment-api-5xx-rate
```

## External Entity ID

The external system's identity for an alert/incident lifecycle.

Example:

```text
payment-api-5xx-rate
host-123-disk-full
```

Rules:

- Used to match CRITICAL, ACKNOWLEDGEMENT, and RECOVERY events from the same source.
- Can be transformed into dedup key.

## Occurrence Count

The number of times the same alert has been received while the matching incident is still open.

Rule:

- Duplicate events increment occurrence count.

## Acknowledge / ACK

A responder action meaning: "I saw this incident and I am taking responsibility."

Rules:

- ACK does not mean the incident is fixed.
- ACK stops normal escalation.
- ACK changes incident status from TRIGGERED to ACKNOWLEDGED.
- ACK creates a timeline event.

## Resolve

A responder or system action meaning the incident is fixed or no longer active.

Rules:

- Resolve changes incident status to RESOLVED.
- Resolve stops escalation.
- Resolve creates a timeline event.
- Recovery events may resolve incidents automatically.

## Cancel

A user action meaning the incident should not be treated as valid.

Examples:

- Test alert accidentally created.
- False positive.
- Incorrect routing.

Rules:

- Cancel stops escalation.
- Cancel creates a timeline event.

## Reopen

An action that makes a resolved incident active again.

MVP decision:

- Reopen is not required for MVP.
- New CRITICAL event after resolution may create a new incident depending on dedup/reopen policy.

## Assignment

The current user/team responsible for an incident.

Rules:

- Assignment may be created by escalation.
- Assignment may be changed manually.
- Assignment is not the same as ACK.

## Responder

A user expected to respond to an incident.

Examples:

- Current on-call user.
- Manually assigned engineer.
- Escalation target.

## On-call

A state where a user is responsible for receiving incident notifications during a time window.

Example:

```text
Siyabend is on call for Backend Team from Monday 09:00 to next Monday 09:00.
```

## Schedule / On-call Schedule

A time-based plan that defines who is on call.

Rules:

- Schedule belongs to organization and usually a team.
- Schedule contains rotations.
- Schedule calculations must be timezone-aware.

## Rotation

A set of participants who take turns being on call.

Examples:

```text
Weekly backend rotation
Daily database rotation
```

Rules:

- Rotation has participants.
- Rotation has start time.
- Rotation has rotation type.
- Rotation may have an end time.

## Rotation Participant

A user participating in a rotation.

Rules:

- Participant order affects on-call calculation.
- Participants must be active users.

## Schedule Override

A temporary change to the normal schedule.

Example:

```text
Ali covers Siyabend's shift from 2026-06-05 09:00 to 2026-06-06 09:00.
```

Rules:

- Override takes priority over base rotation.
- Override must have start and end time.

## Escalation Policy

A policy that defines who should be notified, in what order, and after how much waiting.

Rules:

- A service should have one active escalation policy.
- Escalation rules are ordered.
- Escalation stops when the incident is acknowledged, resolved, or canceled.

## Escalation Rule

One step in an escalation policy.

Fields:

```text
level
waitDuration
targetType
targetId
```

Target types:

```text
USER
SCHEDULE
TEAM
WEBHOOK
```

MVP target types:

```text
USER
SCHEDULE
TEAM
```

## Escalation Task

A scheduled unit of work that executes an escalation rule at a specific time.

Rules:

- Task is created when escalation needs delayed execution.
- Task must check incident state before notifying.
- Task can be PENDING, EXECUTED, CANCELED, or FAILED.

## Notification

A message sent to a user or external endpoint about an incident.

Channels:

```text
EMAIL
TELEGRAM
WEBHOOK
SMS
SLACK
DISCORD
PUSH
```

MVP channels:

```text
EMAIL
TELEGRAM
WEBHOOK
```

## Notification Request

A domain event or record saying a notification should be sent.

Rule:

- Request is not the same as successful delivery.

## Notification Delivery

An attempt to deliver a notification through a specific channel/provider.

Fields:

```text
channel
provider
recipient
status
attemptCount
lastError
sentAt
```

Statuses:

```text
PENDING
SENT
FAILED
RETRYING
CANCELED
```

## Timeline Event

An immutable record of something that happened to an incident.

Examples:

```text
Incident triggered
Duplicate alert received
Notification sent
Notification failed
Escalated to Level 2
Acknowledged by Siyabend
Resolved by Siyabend
```

Rules:

- Timeline events are append-only.
- Timeline events should include actor if available.
- System actions should use actor type SYSTEM.

## Audit Log

A security and compliance-focused log of important configuration and user actions.

Examples:

```text
Escalation policy changed
Integration key rotated
User role changed
Service owner changed
```

Difference from timeline:

- Timeline is incident-focused.
- Audit log is platform/configuration/security-focused.

## Maintenance Window

A planned time window where alerts for a service may be suppressed, downgraded, or prevented from notifying responders.

MVP decision:

- Not required in first MVP.

## Suppression / Silence

A rule that prevents alert notifications or incident creation under specific conditions.

Examples:

- Suppress during deployment window.
- Suppress noisy non-production alerts.

## Heartbeat

A recurring signal expected from an external system. Missing heartbeat can create an alert.

Example:

```text
Backup job must send heartbeat every 24 hours.
```

MVP decision:

- Not required in first MVP.

## Runbook

Operational instructions linked to a service or incident.

Example:

```text
Payment API 5xx runbook
```

MVP decision:

- Runbook URL field can be added to service or incident.

## MTTA

Mean Time To Acknowledge. Average time between incident trigger and ACK.

## MTTR

Mean Time To Resolve. Average time between incident trigger and resolution.

## 4. Domain Rules

## DR-001 Tenant isolation

Every domain object must belong to an organization unless explicitly global.

## DR-002 Service ownership

Every incident must be associated with a monitored service when routing information is available.

## DR-003 Escalation policy requirement

A production service should have one active escalation policy before it can receive CRITICAL incidents.

## DR-004 Event normalization

External payloads must be normalized before incident processing.

## DR-005 Deduplication

If an open incident exists for a dedup key, do not create a new incident.

## DR-006 Recovery

A RECOVERY event resolves the matching open incident.

## DR-007 ACK

ACK changes incident status to ACKNOWLEDGED and stops escalation.

## DR-008 Resolve

Resolve changes incident status to RESOLVED and stops escalation.

## DR-009 Escalation state guard

Before executing an escalation rule, the system must reload the incident and verify it is still eligible for escalation.

## DR-010 Timeline completeness

Every trigger, duplicate, ACK, resolve, cancellation, notification, and escalation must produce a timeline event.

## DR-011 Notification delivery independence

Incident state must not depend on notification delivery success.

## DR-012 Cache safety

Redis cache may accelerate reads or coordination, but PostgreSQL remains the source of truth.

## 5. Naming Guidelines

Use these terms in code:

```text
Incident, not Case
Alert, not Alarm
MonitoredService, not Application
EscalationPolicy, not EscalationConfig
EscalationRule, not EscalationStep unless implementation-specific
OnCallSchedule, not Calendar
ScheduleRotation, not ShiftList
RotationParticipant, not ScheduleUser
NotificationDelivery, not MessageLog
TimelineEvent, not Activity unless UI-specific
```

Use these method names:

```java
acknowledgeIncident()
resolveIncident()
cancelIncident()
triggerIncident()
recordTimelineEvent()
calculateCurrentOnCall()
executeEscalationRule()
requestNotification()
recordDeliveryAttempt()
```

## 6. Anti-corruption Layer Terms

External providers may use different terms. Map them into our language.

| External term | Our term |
| --- | --- |
| alias | dedupKey |
| entity_id | externalEntityId |
| message_type | messageType |
| alert fired | CRITICAL/WARNING event |
| recovery | RECOVERY event / resolve |
| close | resolve or close alert |
| routing key | routingKey |
| responder | escalation target / responder |

## 7. Example Scenario in Ubiquitous Language

1. Grafana sends an External Event through an Integration.
2. Integration Service validates the Integration Key.
3. Integration Service resolves the Routing Key.
4. The payload is normalized into an AlertReceivedEvent.
5. Incident Service checks Deduplication using the Dedup Key.
6. No open Incident exists, so it creates an Alert and an Incident.
7. Incident status becomes TRIGGERED.
8. Timeline Event is recorded.
9. Escalation Service loads the Service's Escalation Policy.
10. Escalation Service calculates the current On-call user from the Schedule.
11. Notification Service sends a Notification.
12. Responder Acknowledges the Incident.
13. Incident status becomes ACKNOWLEDGED.
14. Pending Escalation Tasks are canceled.
15. Responder Resolves the Incident.
16. Incident status becomes RESOLVED.
17. Timeline shows the whole story.
