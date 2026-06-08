package com.urunsiyabend.heimcall.schedule.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OnCallScheduleRepository extends JpaRepository<OnCallSchedule, UUID> {
    List<OnCallSchedule> findByOrganizationId(UUID organizationId);
    Optional<OnCallSchedule> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
