package com.urunsiyabend.heimcall.integration.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RawInboundEventRepository extends JpaRepository<RawInboundEvent, UUID> {
}
