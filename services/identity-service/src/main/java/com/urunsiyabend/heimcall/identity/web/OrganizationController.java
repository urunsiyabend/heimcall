package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.Organization;
import com.urunsiyabend.heimcall.identity.domain.OrganizationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/organizations")
public class OrganizationController {

    private final OrganizationRepository organizations;

    public OrganizationController(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,255}", message = "slug must be lowercase kebab-case") String slug) {
    }

    public record OrganizationResponse(UUID id, String name, String slug, Instant createdAt) {
        static OrganizationResponse of(Organization o) {
            return new OrganizationResponse(o.getId(), o.getName(), o.getSlug(), o.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationResponse create(@Valid @RequestBody CreateRequest req) {
        if (organizations.existsBySlug(req.slug())) {
            throw new ApiExceptions.ConflictException("organization slug already in use: " + req.slug());
        }
        Organization saved = organizations.save(Organization.create(req.name(), req.slug(), Instant.now()));
        return OrganizationResponse.of(saved);
    }

    @GetMapping("/{id}")
    public OrganizationResponse get(@PathVariable UUID id) {
        return organizations.findById(id)
                .map(OrganizationResponse::of)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("organization not found: " + id));
    }
}
