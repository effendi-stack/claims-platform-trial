package com.chubb.claims.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** Seeded reference data; this service never creates users. */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Role role;

    private String name;
    private String email;
    private UUID teamId;

    /** Max amount a handler may approve on their own; null = no limit (managers). */
    private BigDecimal authorityLimit;

    protected AppUser() {
    }

    public UUID getId() { return id; }
    public Role getRole() { return role; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public UUID getTeamId() { return teamId; }
    public BigDecimal getAuthorityLimit() { return authorityLimit; }
}
