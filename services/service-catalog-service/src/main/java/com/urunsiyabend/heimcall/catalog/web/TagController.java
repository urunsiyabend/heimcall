package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.IdentityClient;
import com.urunsiyabend.heimcall.catalog.domain.MonitoredServiceRepository;
import com.urunsiyabend.heimcall.catalog.domain.ServiceTag;
import com.urunsiyabend.heimcall.catalog.domain.ServiceTagRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/services/{serviceId}/tags")
public class TagController {

    private final ServiceTagRepository tags;
    private final MonitoredServiceRepository services;
    private final IdentityClient identity;

    public TagController(ServiceTagRepository tags, MonitoredServiceRepository services, IdentityClient identity) {
        this.tags = tags;
        this.services = services;
        this.identity = identity;
    }

    public record UpsertRequest(@NotBlank String key, @NotBlank String value) {
    }

    public record TagResponse(UUID id, UUID serviceId, String key, String value) {
        static TagResponse of(ServiceTag t) {
            return new TagResponse(t.getId(), t.getServiceId(), t.getKey(), t.getValue());
        }
    }

    @PutMapping
    public TagResponse upsert(@PathVariable UUID orgId, @PathVariable UUID serviceId,
                              @RequestHeader("X-User-Id") UUID callerId, @Valid @RequestBody UpsertRequest req) {
        identity.requireMember(orgId, callerId);
        requireService(orgId, serviceId);
        ServiceTag tag = tags.findByServiceIdAndKey(serviceId, req.key())
                .map(existing -> {
                    existing.updateValue(req.value());
                    return existing;
                })
                .orElseGet(() -> ServiceTag.of(serviceId, req.key(), req.value()));
        return TagResponse.of(tags.save(tag));
    }

    @GetMapping
    public List<TagResponse> list(@PathVariable UUID orgId, @PathVariable UUID serviceId,
                                  @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireService(orgId, serviceId);
        return tags.findByServiceId(serviceId).stream().map(TagResponse::of).toList();
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID serviceId, @PathVariable String key,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        requireService(orgId, serviceId);
        tags.deleteByServiceIdAndKey(serviceId, key);
    }

    private void requireService(UUID orgId, UUID serviceId) {
        if (services.findByIdAndOrganizationId(serviceId, orgId).isEmpty()) {
            throw new ApiExceptions.NotFoundException("service not found in organization: " + serviceId);
        }
    }
}
