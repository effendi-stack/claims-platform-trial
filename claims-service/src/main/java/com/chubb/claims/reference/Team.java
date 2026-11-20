package com.chubb.claims.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "team")
public class Team {

    @Id
    private UUID id;
    private String name;
    private String marketCode;

    protected Team() {
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getMarketCode() { return marketCode; }
}
