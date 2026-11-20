package com.chubb.claims.reference;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.security.CurrentUser;
import com.chubb.claims.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Reference data")
public class ReferenceController {

    private final PolicyRepository policies;
    private final AppUserRepository users;
    private final CurrentUserProvider currentUser;

    public ReferenceController(PolicyRepository policies, AppUserRepository users, CurrentUserProvider currentUser) {
        this.policies = policies;
        this.users = users;
        this.currentUser = currentUser;
    }

    public record PolicyView(UUID id, String policyNumber, ProductLine productLine, String marketCode,
                             String currency, BigDecimal sumInsured, BigDecimal deductible,
                             LocalDate effectiveFrom, LocalDate effectiveTo) {}

    public record HandlerView(UUID id, String name, UUID teamId) {}

    @Operation(summary = "Policies held by the signed-in claimant (to report a claim against)")
    @GetMapping("/policies/mine")
    @PreAuthorize("hasRole('CLAIMANT')")
    public List<PolicyView> myPolicies() {
        return policies.findByHolderIdOrderByPolicyNumber(currentUser.get().id()).stream()
                .map(p -> new PolicyView(p.getId(), p.getPolicyNumber(), p.getProductLine(), p.getMarketCode(),
                        p.getCurrency(), p.getSumInsured(), p.getDeductible(), p.getEffectiveFrom(), p.getEffectiveTo()))
                .toList();
    }

    @Operation(summary = "Handlers in a team (for assignment). Handlers may only list their own team.")
    @GetMapping("/handlers")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public List<HandlerView> handlers(@RequestParam UUID teamId) {
        CurrentUser user = currentUser.get();
        if (user.is(Role.HANDLER) && !teamId.equals(user.teamId())) {
            throw ApiException.forbidden("FORBIDDEN", "Handlers can only see their own team");
        }
        return users.findByRoleAndTeamIdOrderByName(Role.HANDLER, teamId).stream()
                .map(u -> new HandlerView(u.getId(), u.getName(), u.getTeamId()))
                .toList();
    }
}
