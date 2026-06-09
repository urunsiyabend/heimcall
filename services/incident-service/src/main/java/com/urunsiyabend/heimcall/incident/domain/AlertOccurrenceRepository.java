package com.urunsiyabend.heimcall.incident.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertOccurrenceRepository extends JpaRepository<AlertOccurrence, UUID> {

    List<AlertOccurrence> findByAlertIdOrderByReceivedAtAsc(UUID alertId);
}
