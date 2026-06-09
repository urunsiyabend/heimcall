package com.urunsiyabend.heimcall.incident.domain;

import com.urunsiyabend.heimcall.common.domain.AlertStatus;
import com.urunsiyabend.heimcall.common.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A normalized signal inside the platform (plan 4.4, glossary §Alert). The Alert is the deduplicated
 * aggregate: repeated signals for the same {@code (organization, dedupKey)} collapse onto one OPEN
 * alert and bump {@code occurrenceCount} (each individual signal is recorded as an
 * {@link AlertOccurrence}). An Alert MAY exist without an Incident (INFO / non-actionable / suppressed);
 * an actionable alert is linked to the Incident it opened. {@code severity}/{@code title} hold the
 * latest signal's snapshot for display.
 */
@Entity
@Table(name = "alert")
public class Alert {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "incident_id")
    private UUID incidentId;

    @Column(nullable = false)
    private String source;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Column(name = "external_entity_id")
    private String externalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "title")
    private String title;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Alert() {
    }

    /** Open a new alert from the first signal for a dedup key (status OPEN, occurrence 1, no incident yet). */
    public static Alert open(UUID organizationId, String source, String dedupKey, String externalEntityId,
                             Severity severity, String title, Instant at) {
        Alert alert = new Alert();
        alert.id = UUID.randomUUID();
        alert.organizationId = organizationId;
        alert.source = source;
        alert.dedupKey = dedupKey;
        alert.externalEntityId = externalEntityId;
        alert.status = AlertStatus.OPEN;
        alert.severity = severity != null ? severity : Severity.WARNING;
        alert.title = title;
        alert.occurrenceCount = 1;
        alert.firstSeenAt = at;
        alert.lastSeenAt = at;
        alert.createdAt = at;
        alert.updatedAt = at;
        return alert;
    }

    /** A repeated signal collapsed onto this alert: bump the counter and refresh the display snapshot. */
    public void registerOccurrence(Severity severity, String title, Instant at) {
        this.occurrenceCount += 1;
        this.lastSeenAt = at;
        this.updatedAt = at;
        if (severity != null) {
            this.severity = severity;
        }
        if (title != null) {
            this.title = title;
        }
    }

    /** Link the incident this actionable alert opened. */
    public void linkIncident(UUID incidentId) {
        this.incidentId = incidentId;
    }

    public void acknowledge(Instant at) {
        this.status = AlertStatus.ACKNOWLEDGED;
        this.updatedAt = at;
    }

    public void close(Instant at) {
        this.status = AlertStatus.CLOSED;
        this.updatedAt = at;
    }

    public boolean isOpen() {
        return status == AlertStatus.OPEN;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getSource() {
        return source;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getExternalEntityId() {
        return externalEntityId;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public int getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
