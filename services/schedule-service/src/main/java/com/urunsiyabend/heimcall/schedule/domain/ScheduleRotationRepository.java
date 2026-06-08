package com.urunsiyabend.heimcall.schedule.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleRotationRepository extends JpaRepository<ScheduleRotation, UUID> {
    List<ScheduleRotation> findByScheduleIdOrderByPriorityDesc(UUID scheduleId);
    Optional<ScheduleRotation> findByIdAndScheduleId(UUID id, UUID scheduleId);
}
