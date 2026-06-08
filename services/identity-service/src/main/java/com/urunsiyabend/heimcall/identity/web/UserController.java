package com.urunsiyabend.heimcall.identity.web;

import com.urunsiyabend.heimcall.identity.domain.AppUser;
import com.urunsiyabend.heimcall.identity.domain.AppUserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/v1/users")
public class UserController {

    private final AppUserRepository users;

    public UserController(AppUserRepository users) {
        this.users = users;
    }

    public record CreateRequest(@NotBlank @Email String email, @NotBlank String displayName) {
    }

    public record UserResponse(UUID id, String email, String displayName, Instant createdAt) {
        static UserResponse of(AppUser u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new ApiExceptions.ConflictException("email already in use: " + req.email());
        }
        AppUser saved = users.save(AppUser.create(req.email(), req.displayName(), Instant.now()));
        return UserResponse.of(saved);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return users.findById(id)
                .map(UserResponse::of)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("user not found: " + id));
    }
}
