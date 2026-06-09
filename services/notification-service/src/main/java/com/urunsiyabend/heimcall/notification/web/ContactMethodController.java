package com.urunsiyabend.heimcall.notification.web;

import com.urunsiyabend.heimcall.notification.IdentityClient;
import com.urunsiyabend.heimcall.notification.domain.ContactMethod;
import com.urunsiyabend.heimcall.notification.domain.ContactMethodRepository;
import com.urunsiyabend.heimcall.notification.domain.NotificationChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Manages a user's contact methods (the notification context owns these, plan 4.7). Org-scoped and
 * member-gated via the header-context stub; the target user is verified to belong to the org.
 */
@RestController
@RequestMapping("/v1/organizations/{orgId}/users/{userId}/contact-methods")
public class ContactMethodController {

    private final ContactMethodRepository contactMethods;
    private final IdentityClient identity;

    public ContactMethodController(ContactMethodRepository contactMethods, IdentityClient identity) {
        this.contactMethods = contactMethods;
        this.identity = identity;
    }

    public record CreateRequest(@NotNull NotificationChannel channel, @NotBlank String destination, String label) {
    }

    public record UpdateRequest(@NotNull Boolean enabled) {
    }

    public record ContactMethodResponse(UUID id, UUID organizationId, UUID userId, NotificationChannel channel,
                                        String destination, String label, boolean enabled,
                                        Instant createdAt, Instant updatedAt) {
        static ContactMethodResponse of(ContactMethod c) {
            return new ContactMethodResponse(c.getId(), c.getOrganizationId(), c.getUserId(), c.getChannel(),
                    c.getDestination(), c.getLabel(), c.isEnabled(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactMethodResponse create(@PathVariable UUID orgId, @PathVariable UUID userId,
                                        @RequestHeader("X-User-Id") UUID callerId,
                                        @Valid @RequestBody CreateRequest req) {
        identity.requireMember(orgId, callerId);
        identity.requireOrgUser(orgId, userId);
        ContactMethod saved = contactMethods.save(
                ContactMethod.create(orgId, userId, req.channel(), req.destination(), req.label(), Instant.now()));
        return ContactMethodResponse.of(saved);
    }

    @GetMapping
    public List<ContactMethodResponse> list(@PathVariable UUID orgId, @PathVariable UUID userId,
                                            @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        return contactMethods.findByOrganizationIdAndUserId(orgId, userId).stream()
                .map(ContactMethodResponse::of).toList();
    }

    @PutMapping("/{id}")
    public ContactMethodResponse update(@PathVariable UUID orgId, @PathVariable UUID userId, @PathVariable UUID id,
                                        @RequestHeader("X-User-Id") UUID callerId,
                                        @Valid @RequestBody UpdateRequest req) {
        identity.requireMember(orgId, callerId);
        ContactMethod cm = load(orgId, userId, id);
        cm.setEnabled(req.enabled(), Instant.now());
        return ContactMethodResponse.of(contactMethods.save(cm));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID userId, @PathVariable UUID id,
                       @RequestHeader("X-User-Id") UUID callerId) {
        identity.requireMember(orgId, callerId);
        contactMethods.delete(load(orgId, userId, id));
    }

    private ContactMethod load(UUID orgId, UUID userId, UUID id) {
        return contactMethods.findByIdAndOrganizationIdAndUserId(id, orgId, userId)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("contact method not found: " + id));
    }
}
