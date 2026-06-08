package com.urunsiyabend.heimcall.schedule.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface RotationParticipantRepository extends JpaRepository<RotationParticipant, UUID> {
    List<RotationParticipant> findByRotationIdOrderByPositionAsc(UUID rotationId);
    boolean existsByRotationIdAndPosition(UUID rotationId, int position);

    @Transactional
    void deleteByRotationIdAndUserId(UUID rotationId, UUID userId);
}
