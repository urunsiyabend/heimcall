package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.common.security.AuthPrincipal;
import com.urunsiyabend.heimcall.common.security.CurrentUser;
import com.urunsiyabend.heimcall.common.security.JwtSupport;
import com.urunsiyabend.heimcall.identity.domain.AppUser;
import com.urunsiyabend.heimcall.identity.domain.AppUserRepository;
import com.urunsiyabend.heimcall.identity.domain.Membership;
import com.urunsiyabend.heimcall.identity.domain.MembershipRepository;
import com.urunsiyabend.heimcall.identity.domain.Organization;
import com.urunsiyabend.heimcall.identity.domain.OrganizationRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real JWT auth (Phase 7 ticket 2). Register/login mint an access + refresh token pair; refresh
 * exchanges a valid refresh token for a new access token; {@code /me} returns the authenticated user
 * and their org memberships. Password is BCrypt-hashed at rest.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final PasswordEncoder passwordEncoder;
    private final JwtSupport jwt;

    public AuthController(AppUserRepository users, MembershipRepository memberships,
                          OrganizationRepository organizations, PasswordEncoder passwordEncoder, JwtSupport jwt) {
        this.users = users;
        this.memberships = memberships;
        this.organizations = organizations;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
    }

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank String displayName,
                                  @NotBlank @Size(min = 8, max = 100) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record UserView(UUID id, String email, String displayName) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getEmail(), u.getDisplayName());
        }
    }

    public record TokenResponse(String accessToken, String refreshToken, UserView user) {
    }

    public record MembershipView(UUID organizationId, String organizationName, String role) {
    }

    public record MeResponse(UserView user, List<MembershipView> memberships) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ApiExceptions.ConflictException("email already in use: " + req.email());
        }
        AppUser user = users.save(AppUser.register(
                req.email(), req.displayName(), passwordEncoder.encode(req.password()), Instant.now()));
        return tokensFor(user);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        AppUser user = users.findByEmail(req.email())
                .filter(u -> u.getPasswordHash() != null
                        && passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiExceptions.UnauthorizedException("invalid email or password"));
        return tokensFor(user);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        Claims claims;
        try {
            claims = jwt.parse(req.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiExceptions.UnauthorizedException("invalid refresh token");
        }
        if (!JwtSupport.TYPE_REFRESH.equals(claims.get("type", String.class))) {
            throw new ApiExceptions.UnauthorizedException("not a refresh token");
        }
        AppUser user = users.findById(UUID.fromString(claims.getSubject()))
                .orElseThrow(() -> new ApiExceptions.UnauthorizedException("user no longer exists"));
        // New access token; the caller keeps using its existing (still-valid) refresh token.
        return new TokenResponse(
                jwt.issueAccess(user.getId(), user.getEmail(), user.getDisplayName()),
                req.refreshToken(),
                UserView.of(user));
    }

    @GetMapping("/me")
    public MeResponse me() {
        AuthPrincipal principal = CurrentUser.get();
        AppUser user = users.findById(principal.userId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("user not found"));
        List<MembershipView> views = memberships.findByUserId(user.getId()).stream()
                .map(this::toMembershipView)
                .toList();
        return new MeResponse(UserView.of(user), views);
    }

    private TokenResponse tokensFor(AppUser user) {
        return new TokenResponse(
                jwt.issueAccess(user.getId(), user.getEmail(), user.getDisplayName()),
                jwt.issueRefresh(user.getId()),
                UserView.of(user));
    }

    private MembershipView toMembershipView(Membership m) {
        String orgName = organizations.findById(m.getOrganizationId())
                .map(Organization::getName)
                .orElse(null);
        return new MembershipView(m.getOrganizationId(), orgName, m.getRole().name());
    }
}
