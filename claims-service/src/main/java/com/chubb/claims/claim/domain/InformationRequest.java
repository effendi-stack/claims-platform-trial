package com.chubb.claims.claim.domain;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A handler's question to the claimant; the claimant answers it once. */
@Entity
@Table(name = "information_request")
public class InformationRequest extends BaseEntity {

    public enum Status { OPEN, ANSWERED, CANCELLED }

    @Id
    private UUID id;
    private UUID claimId;
    private UUID requestedBy;
    private String question;
    private Instant requestedAt;
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String responseText;
    private Instant respondedAt;

    protected InformationRequest() {
    }

    public InformationRequest(UUID claimId, UUID requestedBy, String question, Instant dueAt, Instant now) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.requestedBy = requestedBy;
        this.question = question;
        this.dueAt = dueAt;
        this.requestedAt = now;
        this.status = Status.OPEN;
    }

    public void answer(String text, Instant now) {
        if (status != Status.OPEN) {
            throw ApiException.conflict("REQUEST_NOT_OPEN", "This information request has already been closed");
        }
        this.responseText = text;
        this.respondedAt = now;
        this.status = Status.ANSWERED;
    }

    public void cancel() {
        if (status == Status.OPEN) {
            status = Status.CANCELLED;
        }
    }

    @Override
    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getQuestion() { return question; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getDueAt() { return dueAt; }
    public Status getStatus() { return status; }
    public String getResponseText() { return responseText; }
    public Instant getRespondedAt() { return respondedAt; }
}
