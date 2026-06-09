-- Routing key: the alert routingKey that maps an inbound signal to this service (Phase 5).
-- incident-service resolves routingKey -> service -> escalation_policy_id at trigger time.
-- Unique per org where set; null means the service is not directly routable yet.
ALTER TABLE monitored_service ADD COLUMN routing_key VARCHAR(255);
CREATE UNIQUE INDEX ux_monitored_service_routing_key
    ON monitored_service (organization_id, routing_key)
    WHERE routing_key IS NOT NULL;
