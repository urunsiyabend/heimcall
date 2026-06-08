package com.urunsiyabend.heimcall.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** A key/value tag on a monitored service. Unique per (service, key). */
@Entity
@Table(name = "service_tag")
public class ServiceTag {

    @Id
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "tag_key", nullable = false)
    private String key;

    @Column(name = "tag_value", nullable = false)
    private String value;

    protected ServiceTag() {
    }

    public static ServiceTag of(UUID serviceId, String key, String value) {
        ServiceTag t = new ServiceTag();
        t.id = UUID.randomUUID();
        t.serviceId = serviceId;
        t.key = key;
        t.value = value;
        return t;
    }

    public void updateValue(String value) {
        this.value = value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
