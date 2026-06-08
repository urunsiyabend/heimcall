package com.urunsiyabend.heimcall.catalog.web;

import com.urunsiyabend.heimcall.catalog.IdentityClient;
import com.urunsiyabend.heimcall.catalog.domain.MonitoredService;
import com.urunsiyabend.heimcall.catalog.domain.MonitoredServiceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations/{orgId}/services")
public class ServiceController {

    private final MonitoredServiceRepository services;
    private final IdentityClient identity;

    public ServiceController(MonitoredServiceRepository services, IdentityClient identity) {
        this.services = services;
        this.identity = identity;
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,255}", message = "slug must be lowercase kebab-case") String slug,
            String description) {
    }

    public record UpdateRequest(@NotBlank String name, String description) {
    }

    public record AssignOwnerRequest(@NotNull UUID teamId) {
    }

    public record AssignPolicyRequest(@NotNull UUID escalationPolicyId) {
    }

    public record ServiceResponse(UUID id, UUID organizationId, String name, String slug, String description,
                                  UUID ownerTeamId, UUID escalationPolicyId, Instant createdAt, Instant updatedAt) {
        static ServiceResponse of(MonitoredService s) {
            return new ServiceResponse(s.getId(), s.getOrganizationId(), s.getName(), s.getSlug(),
                    s.getDescription(), s.getOwnerTeamId(), s.getEscalationPolicyId(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse create(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId,
                                  @Valid @RequestBody CreateRequest req) {
        identity.requireMember(orgId, callerId);
        if (services.existsByOrganizationIdAndSlug(orgId, req.slug())) {
            throw new ApiExceptions.ConflictException("service slug already in use: " + req.slug());
        }
        MonitoredService saved = services.save(
                MonitoredService.create(orgId, req.name(), req.slug(), req.description(), Instant.now()));
        return ServiceResponse.of(saved);
    }

    @GetMapping
    public List<ServiceResponse> list(@PathVariable UUID orgId, @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return services.findByOrganizationId(orgId).stream().map(ServiceResponse::of).toList();
    }

    @GetMapping("/{id}")
    public ServiceResponse get(@PathVariable UUID orgId, @PathVariable UUID id,
                               @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return ServiceResponse.of(load(orgId, id));
    }

    @PutMapping("/{id}")
    public ServiceResponse update(@PathVariable UUID orgId, @PathVariable UUID id,
                                  @RequestHeader("X-User-Id") UUID callerId, @Valid @RequestBody UpdateRequest req) {
        identity.requireMember(orgId, callerId);
        MonitoredService s = load(orgId, id);
        s.update(req.name(), req.description(), Instant.now());
        return ServiceResponse.of(services.save(s));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID id,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        services.delete(load(orgId, id));
    }

    @PutMapping("/{id}/owner")
    public ServiceResponse assignOwner(@PathVariable UUID orgId, @PathVariable UUID id,
                                       @RequestHeader("X-User-Id") UUID callerId,
                                       @Valid @RequestBody AssignOwnerRequest req) {
        identity.requireMember(orgId, callerId);
        identity.requireTeamInOrg(orgId, req.teamId());
        MonitoredService s = load(orgId, id);
        s.assignOwner(req.teamId(), Instant.now());
        return ServiceResponse.of(services.save(s));
    }

    /** Placeholder until escalation-service exists: the id is stored but not validated. */
    @PutMapping("/{id}/escalation-policy")
    public ServiceResponse assignPolicy(@PathVariable UUID orgId, @PathVariable UUID id,
                                        @RequestHeader("X-User-Id") UUID callerId,
                                        @Valid @RequestBody AssignPolicyRequest req) {
        identity.requireMember(orgId, callerId);
        MonitoredService s = load(orgId, id);
        s.assignEscalationPolicy(req.escalationPolicyId(), Instant.now());
        return ServiceResponse.of(services.save(s));
    }

    private MonitoredService load(UUID orgId, UUID id) {
        return services.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("service not found in organization: " + id));
    }
}
