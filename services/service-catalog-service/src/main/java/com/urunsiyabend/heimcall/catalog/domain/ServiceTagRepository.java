package com.urunsiyabend.heimcall.catalog.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceTagRepository extends JpaRepository<ServiceTag, UUID> {
    List<ServiceTag> findByServiceId(UUID serviceId);
    Optional<ServiceTag> findByServiceIdAndKey(UUID serviceId, String key);

    @Transactional
    void deleteByServiceIdAndKey(UUID serviceId, String key);
}
