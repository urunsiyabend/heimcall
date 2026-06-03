# Acceptance Test Document

Project: Incident Management / On-call Platform
Version: 0.1
Format: Gherkin-style scenarios plus implementation notes

## 1. Scope

This document defines acceptance tests for the MVP. These scenarios should guide backend, frontend, integration, and end-to-end tests.

The MVP covers:

- Organization/team/service setup
- Integration ingestion
- Alert normalization
- Deduplication
- Incident lifecycle
- Timeline
- Schedules
- Escalation
- Notification delivery
- Kubernetes/cloud-native operational behavior

## 2. Test Data

Default organization:

```text
Organization: Acme Engineering
```

Users:

```text
Siyabend - responder
Ali - responder
Ayşe - team admin
```

Team:

```text
Backend Team
```

Service:

```text
Payment API
```

Schedule:

```text
Backend Weekly Rotation
Timezone: Europe/Istanbul
Participants: Siyabend, Ali
Start: 2026-06-01T09:00:00+03:00
```

Escalation policy:

```text
Backend Critical Policy
Level 1: Backend Weekly Rotation, wait 0 minutes
Level 2: Ali, wait 5 minutes
```

Integration:

```text
Grafana Generic Webhook
Routing Key: payment-api-critical
```

## 3. Organization and Access

## AT-001 Tenant isolation

Scenario: User cannot see another organization's incidents

Given Siyabend belongs to Acme Engineering
And another organization exists named Beta Engineering
And Beta Engineering has an incident
When Siyabend requests the incident list
Then the response must not include Beta Engineering incidents

Acceptance criteria:

- Query filters by organization id.
- API does not leak cross-tenant data.

## AT-002 Team admin can create service

Scenario: Team admin creates a monitored service

Given Ayşe is TEAM_ADMIN of Backend Team
When she creates a service named Payment API
Then the service is created under Acme Engineering
And the service is owned by Backend Team

## AT-003 Responder cannot rotate integration key

Scenario: Non-admin attempts privileged integration action

Given Siyabend is RESPONDER but not TEAM_ADMIN
When he attempts to rotate an integration key
Then the request is rejected with 403 Forbidden
And an audit log entry is recorded

## 4. Integration Ingestion

## AT-010 Valid CRITICAL event is accepted

Scenario: External monitoring system sends valid CRITICAL event

Given a valid integration key exists
And routing key payment-api-critical maps to Payment API
When Grafana sends:

```json
{
  "messageType": "CRITICAL",
  "entityId": "payment-api-5xx-rate",
  "entityDisplayName": "Payment API 5xx rate high",
  "stateMessage": "Error rate exceeded 5% for 5 minutes",
  "severity": "CRITICAL",
  "source": "grafana"
}
```

Then the platform accepts the request
And a raw inbound event is stored
And an AlertReceivedEvent is published

## AT-011 Invalid integration key is rejected

Scenario: External system sends event with invalid key

Given no integration exists for key invalid-key
When an event is posted with invalid-key
Then the request is rejected with 401 Unauthorized
And no alert is created
And no incident is created

## AT-012 Invalid routing key is rejected

Scenario: Valid integration key but invalid routing key

Given a valid integration key exists
And routing key unknown-route does not exist
When an event is posted to unknown-route
Then the request is rejected with 404 Not Found or 422 Unprocessable Entity
And no incident is created

## AT-013 INFO event does not create incident

Scenario: Informational event is recorded but not turned into incident

Given a valid integration key exists
When an INFO event is received for Payment API
Then an alert or raw event is recorded according to configuration
And no incident is created by default

## AT-014 RECOVERY without open incident does not create incident

Scenario: Recovery event arrives before any trigger

Given no open incident exists for entityId payment-api-5xx-rate
When a RECOVERY event is received for entityId payment-api-5xx-rate
Then no new incident is created
And a timeline event is not created unless linked to an existing incident
And the raw event is stored for debugging

## 5. Deduplication

## AT-020 Duplicate CRITICAL events update existing incident

Scenario: Same event arrives repeatedly

Given a CRITICAL event created incident INC-1001 with dedup key grafana:payment-api-5xx-rate
When the same CRITICAL event arrives again
Then no new incident is created
And INC-1001 occurrence count is incremented
And a timeline event "Duplicate alert received" is added

## AT-021 Different dedup key creates separate incident

Scenario: Same service has different problem

Given Payment API has an open incident with dedup key grafana:payment-api-5xx-rate
When a CRITICAL event arrives with dedup key grafana:payment-api-latency-high
Then a new incident is created

## AT-022 Resolved incident is not silently mutated

Scenario: CRITICAL event arrives after previous incident resolved

Given incident INC-1001 with dedup key grafana:payment-api-5xx-rate is RESOLVED
When a new CRITICAL event arrives with the same dedup key
Then the system creates a new incident or reopens the old incident according to configured reopen policy
And the behavior is recorded in timeline

MVP expected behavior:

- Create a new incident.

## 6. Incident Lifecycle

## AT-030 Incident starts as TRIGGERED

Scenario: Actionable alert creates incident

Given a valid CRITICAL event is received
When no matching open incident exists
Then a new incident is created
And its status is TRIGGERED
And its severity is CRITICAL
And its service is Payment API

## AT-031 Responder acknowledges incident

Scenario: Responder ACKs triggered incident

Given incident INC-1001 is TRIGGERED
When Siyabend acknowledges INC-1001
Then incident status becomes ACKNOWLEDGED
And acknowledgedBy is Siyabend
And acknowledgedAt is set
And a timeline event is recorded
And pending escalation tasks are canceled

## AT-032 ACK is idempotent

Scenario: Same ACK command is sent twice

Given incident INC-1001 is ACKNOWLEDGED by Siyabend
When the same ACK command is submitted again with the same idempotency key
Then the response is successful
And no duplicate timeline event is created

## AT-033 Resolve triggered incident

Scenario: Incident is resolved without explicit ACK

Given incident INC-1001 is TRIGGERED
When Siyabend resolves INC-1001
Then incident status becomes RESOLVED
And resolvedBy is Siyabend
And resolvedAt is set
And escalation is stopped

## AT-034 Resolve acknowledged incident

Scenario: Acknowledged incident is resolved

Given incident INC-1001 is ACKNOWLEDGED
When Siyabend resolves INC-1001
Then incident status becomes RESOLVED
And a timeline event is recorded

## AT-035 Cannot ACK resolved incident

Scenario: ACK after resolution is invalid

Given incident INC-1001 is RESOLVED
When Siyabend tries to acknowledge INC-1001
Then the request is rejected with 409 Conflict
And incident status remains RESOLVED

## AT-036 Recovery resolves matching incident

Scenario: External recovery closes active incident

Given incident INC-1001 is TRIGGERED with externalEntityId payment-api-5xx-rate
When a RECOVERY event arrives with externalEntityId payment-api-5xx-rate
Then INC-1001 status becomes RESOLVED
And resolvedBy is SYSTEM
And a timeline event is recorded

## 7. Timeline

## AT-040 Trigger creates timeline event

Scenario: Incident created from alert

Given a CRITICAL alert is received
When an incident is created
Then timeline contains "Incident triggered"
And the event includes source grafana

## AT-041 Notification creates timeline event

Scenario: Notification requested and sent

Given incident INC-1001 is TRIGGERED
When notification is sent to Siyabend
Then timeline contains "Notification sent"
And the event includes channel and recipient

## AT-042 Escalation creates timeline event

Scenario: Incident escalates to second level

Given incident INC-1001 is TRIGGERED
And Level 1 wait duration has expired
When escalation proceeds to Level 2
Then timeline contains "Escalated to Level 2"

## 8. Scheduling

## AT-050 Weekly rotation returns first participant in first week

Scenario: First week of schedule

Given Backend Weekly Rotation starts at 2026-06-01T09:00:00+03:00
And participants are Siyabend, Ali
When current on-call is requested for 2026-06-03T12:00:00+03:00
Then Siyabend is returned

## AT-051 Weekly rotation returns second participant in second week

Scenario: Second week of schedule

Given Backend Weekly Rotation starts at 2026-06-01T09:00:00+03:00
And participants are Siyabend, Ali
When current on-call is requested for 2026-06-09T12:00:00+03:00
Then Ali is returned

## AT-052 Timezone is respected

Scenario: Schedule uses Europe/Istanbul timezone

Given schedule timezone is Europe/Istanbul
When current on-call is calculated near rotation boundary
Then calculation uses Europe/Istanbul local time

## AT-053 Override takes priority

Scenario: Ali covers Siyabend's shift

Given Siyabend is normally on call
And an override assigns Ali from 2026-06-03T09:00:00+03:00 to 2026-06-04T09:00:00+03:00
When current on-call is requested for 2026-06-03T12:00:00+03:00
Then Ali is returned

## 9. Escalation

## AT-060 Level 1 notification requested immediately

Scenario: New incident triggers escalation policy

Given Payment API uses Backend Critical Policy
And Backend Critical Policy Level 1 target is Backend Weekly Rotation
When incident INC-1001 is triggered
Then escalation-service requests notification for current on-call user

## AT-061 Level 2 executes when no ACK

Scenario: No one acknowledges Level 1

Given incident INC-1001 is TRIGGERED
And Level 1 notification was sent to Siyabend
And Level 2 target is Ali after 5 minutes
When 5 minutes pass without ACK
Then notification is requested for Ali
And timeline contains "Escalated to Level 2"

## AT-062 Escalation stops after ACK

Scenario: Responder acknowledges before Level 2

Given incident INC-1001 is TRIGGERED
And Level 2 task is scheduled for 5 minutes later
When Siyabend acknowledges the incident before 5 minutes
Then Level 2 task is canceled
And no notification is sent to Ali

## AT-063 Escalation worker rechecks incident state

Scenario: Race between ACK and escalation worker

Given Level 2 escalation task is due
And Siyabend acknowledges the incident at nearly the same time
When escalation worker executes the task
Then it reloads incident state
And if status is ACKNOWLEDGED, it cancels task without notification

## AT-064 Escalation task is idempotent

Scenario: Worker receives same task twice

Given escalation task TASK-1 has been executed
When worker attempts to execute TASK-1 again
Then no duplicate notification is requested
And task remains EXECUTED

## 10. Notifications

## AT-070 Notification delivery success is recorded

Scenario: Email provider accepts message

Given notification request exists for Siyabend via EMAIL
When email provider returns success
Then NotificationDelivery status becomes SENT
And sentAt is set

## AT-071 Notification failure is retried

Scenario: Provider timeout occurs

Given notification request exists for Siyabend via TELEGRAM
When provider times out
Then delivery status becomes RETRYING
And retry count is incremented
And message is retried according to retry policy

## AT-072 Exhausted notification goes to dead-letter

Scenario: Provider fails repeatedly

Given notification delivery fails max retry attempts
When retry budget is exhausted
Then status becomes FAILED
And failure event is published to dead-letter topic
And timeline contains "Notification failed"

## AT-073 Notification failure does not change incident status

Scenario: Notification provider fails

Given incident INC-1001 is TRIGGERED
When notification delivery fails
Then incident status remains TRIGGERED
And escalation policy continues according to configuration

## 11. Kafka and Event Processing

## AT-080 AlertReceivedEvent is consumed once logically

Scenario: Kafka redelivery occurs

Given alert.received.v1 event has eventId E1
When incident-service consumes E1 twice
Then only one incident is created
And duplicate processing is ignored or treated idempotently

## AT-081 Consumer failure retries safely

Scenario: incident-service fails during event processing

Given alert.received.v1 event is consumed
When database is temporarily unavailable
Then event processing is retried
And no partial duplicate incident is created after recovery

## AT-082 Poison message goes to dead-letter topic

Scenario: Event cannot be processed

Given malformed event exists in alert.received.v1
When consumer cannot deserialize or validate it after configured retries
Then event is published to dead-letter topic
And an operational metric is incremented

## 12. Redis and Coordination

## AT-090 Idempotency key prevents duplicate command execution

Scenario: Client retries ACK request

Given incident INC-1001 is TRIGGERED
When client sends ACK request with Idempotency-Key K1
And retries the same request with K1
Then ACK logic runs once
And both responses are consistent

## AT-091 Rate limit protects integration endpoint

Scenario: Integration sends too many events

Given integration key has limit 100 requests per minute
When 101 requests arrive within one minute
Then the 101st request is rejected with 429 Too Many Requests

## AT-092 Distributed lock prevents double escalation processing

Scenario: Two escalation-service pods run workers

Given escalation task TASK-1 is due
When two pods attempt to execute TASK-1
Then only one pod obtains execution rights
And only one notification is requested

## 13. Kubernetes and Operations

## AT-100 Service starts with readiness probe healthy

Scenario: incident-service starts successfully

Given incident-service is deployed to Kubernetes
When application starts and database migration completes
Then readiness probe becomes healthy
And liveness probe remains healthy

## AT-101 Pod restart does not lose incidents

Scenario: incident-service pod restarts

Given incident INC-1001 exists in PostgreSQL
When incident-service pod is restarted
Then INC-1001 is still available after restart

## AT-102 Horizontal scaling does not duplicate event effects

Scenario: Two incident-service replicas consume events

Given incident-service has two replicas
When one AlertReceivedEvent is published
Then only one incident is created

## AT-103 Kafka lag is observable

Scenario: Consumer falls behind

Given notification-service consumer group has lag
When metrics are scraped
Then Kafka consumer lag metric is available in Prometheus

## 14. UI Acceptance Tests

## AT-110 Incident list shows open incidents

Scenario: Responder opens incident list

Given incident INC-1001 is TRIGGERED
When Siyabend opens the incident list
Then INC-1001 is visible
And severity, service, status, and triggered time are shown

## AT-111 Incident detail shows timeline

Scenario: Responder opens incident detail

Given incident INC-1001 has timeline events
When Siyabend opens incident detail
Then timeline events are shown in chronological order

## AT-112 ACK button works

Scenario: Responder acknowledges from UI

Given incident INC-1001 is TRIGGERED
When Siyabend clicks ACK
Then incident status changes to ACKNOWLEDGED
And UI updates without full page reload if realtime is enabled

## AT-113 Resolve button works

Scenario: Responder resolves from UI

Given incident INC-1001 is ACKNOWLEDGED
When Siyabend clicks Resolve
Then incident status changes to RESOLVED
And incident disappears from default open incidents view

## 15. Reporting Acceptance Tests

## AT-120 MTTA is calculated

Scenario: Incident is acknowledged

Given incident triggeredAt is 12:00
And acknowledgedAt is 12:05
When report calculates time to acknowledge
Then MTTA contribution is 5 minutes

## AT-121 MTTR is calculated

Scenario: Incident is resolved

Given incident triggeredAt is 12:00
And resolvedAt is 12:30
When report calculates time to resolve
Then MTTR contribution is 30 minutes

## 16. Security Acceptance Tests

## AT-130 Integration key is not returned after creation

Scenario: Admin views integration details

Given an integration key was generated
When admin later views integration details
Then the raw key is not visible
And only masked key metadata is shown

## AT-131 API rejects oversized payload

Scenario: External system sends huge payload

Given max payload size is configured
When request payload exceeds max size
Then API rejects request with 413 Payload Too Large
And no event is published

## AT-132 Unauthorized lifecycle action is rejected

Scenario: Viewer tries to resolve incident

Given user has VIEWER role
When user attempts to resolve incident
Then request is rejected with 403 Forbidden
And no timeline lifecycle event is created

## 17. End-to-End Smoke Test

## AT-999 Full happy path

Scenario: External alert creates incident and responder resolves it

Given Acme Engineering exists
And Backend Team exists
And Siyabend is current on-call
And Payment API exists
And Payment API has Backend Critical Policy
And Grafana integration exists
When Grafana sends a CRITICAL event for Payment API
Then incident INC-1001 is created
And notification is sent to Siyabend
When Siyabend acknowledges INC-1001
Then escalation stops
When Siyabend resolves INC-1001
Then incident status becomes RESOLVED
And timeline contains trigger, notification, ACK, and resolve events

## 18. Automation Notes

Recommended test stack:

- JUnit 5
- AssertJ
- Mockito for isolated unit tests
- Testcontainers for PostgreSQL, Kafka, Redis
- WireMock for notification providers
- Spring Cloud Contract for API/event contracts
- Playwright or Cypress for UI acceptance tests

Recommended pipeline stages:

```text
unit-tests
integration-tests
contract-tests
e2e-smoke-tests
container-build
helm-template-validation
kubernetes-smoke-test
```

## 19. Minimum Release Gate

The product must not be considered MVP-complete until these tests pass:

```text
AT-010
AT-020
AT-030
AT-031
AT-034
AT-036
AT-040
AT-050
AT-060
AT-061
AT-062
AT-070
AT-080
AT-090
AT-100
AT-999
```
