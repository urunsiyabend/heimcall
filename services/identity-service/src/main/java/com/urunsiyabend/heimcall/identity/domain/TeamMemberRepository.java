package com.urunsiyabend.heimcall.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID> {
    List<TeamMember> findByTeamId(UUID teamId);
    boolean existsByTeamIdAndUserId(UUID teamId, UUID userId);
}
