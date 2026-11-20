package com.chubb.claims.security;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.reference.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * DEV ONLY: password-less login as a seeded user, so the demo can switch between
 * claimant, handler and manager. Disabled with security.dev-login.enabled=false;
 * in production tokens come from the corporate identity provider.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "security.dev-login.enabled", havingValue = "true")
@Tag(name = "Auth (dev only)")
public class DevAuthController {

    private final AppUserRepository users;
    private final JwtTokenService tokens;

    public DevAuthController(AppUserRepository users, JwtTokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public record DevLoginRequest(@NotBlank @Email String email) {}

    public record UserView(UUID id, String name, String email, Role role, UUID teamId) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.getTeamId());
        }
    }

    public record LoginResponse(String token, UserView user) {}

    @Operation(summary = "Issue a token for a seeded demo user")
    @PostMapping("/dev-login")
    public LoginResponse login(@Valid @RequestBody DevLoginRequest request) {
        AppUser user = users.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "No demo user with that email"));
        return new LoginResponse(tokens.issue(user), UserView.of(user));
    }

    @Operation(summary = "List seeded demo users (for the login screen)")
    @GetMapping("/demo-users")
    public List<UserView> demoUsers() {
        return users.findAll(Sort.by("role", "name")).stream().map(UserView::of).toList();
    }
}
