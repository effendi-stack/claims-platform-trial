package com.chubb.claims.reference;

public enum ProductLine {
    MOTOR("MOT"), PROPERTY("PRP");

    private final String code;

    ProductLine(String code) {
        this.code = code;
    }

    /** Short code used in human-readable claim numbers, e.g. SG-MOT-2026-001000. */
    public String code() {
        return code;
    }
}
