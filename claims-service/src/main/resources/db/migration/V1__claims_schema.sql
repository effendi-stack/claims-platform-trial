-- claims-service schema. See docs/architecture.md section 7.
-- Money is NUMERIC(19,2) with an explicit ISO currency; never floating point.

-- ---------------------------------------------------------------- reference
CREATE TABLE market (
    code      VARCHAR(2)  PRIMARY KEY,
    name      VARCHAR(80) NOT NULL,
    currency  CHAR(3)     NOT NULL,
    timezone  VARCHAR(40) NOT NULL
);

CREATE TABLE team (
    id           UUID PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    market_code  VARCHAR(2)   NOT NULL REFERENCES market(code)
);

CREATE TABLE app_user (
    id               UUID PRIMARY KEY,
    role             VARCHAR(20)  NOT NULL CHECK (role IN ('CLAIMANT', 'HANDLER', 'MANAGER')),
    name             VARCHAR(120) NOT NULL,
    email            VARCHAR(200) NOT NULL UNIQUE,
    team_id          UUID REFERENCES team(id),
    authority_limit  NUMERIC(19,2),
    CONSTRAINT handler_has_team CHECK (role <> 'HANDLER' OR team_id IS NOT NULL)
);

CREATE TABLE policy (
    id              UUID PRIMARY KEY,
    policy_number   VARCHAR(40)   NOT NULL UNIQUE,
    holder_id       UUID          NOT NULL REFERENCES app_user(id),
    product_line    VARCHAR(20)   NOT NULL CHECK (product_line IN ('MOTOR', 'PROPERTY')),
    market_code     VARCHAR(2)    NOT NULL REFERENCES market(code),
    currency        CHAR(3)       NOT NULL,
    sum_insured     NUMERIC(19,2) NOT NULL CHECK (sum_insured > 0),
    deductible      NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (deductible >= 0),
    effective_from  DATE          NOT NULL,
    effective_to    DATE          NOT NULL,
    CHECK (effective_to > effective_from)
);
CREATE INDEX ix_policy_holder ON policy(holder_id);

-- ---------------------------------------------------------------- claims
CREATE SEQUENCE claim_number_seq START 1000;

CREATE TABLE claim (
    id                   UUID PRIMARY KEY,
    claim_number         VARCHAR(30)   NOT NULL UNIQUE,         -- e.g. SG-MOT-2026-001000
    policy_id            UUID          NOT NULL REFERENCES policy(id),
    claimant_id          UUID          NOT NULL REFERENCES app_user(id),
    -- denormalised from policy for filtering without joins
    product_line         VARCHAR(20)   NOT NULL,
    market_code          VARCHAR(2)    NOT NULL REFERENCES market(code),
    currency             CHAR(3)       NOT NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN
                           ('SUBMITTED', 'IN_REVIEW', 'AWAITING_INFO', 'APPROVED', 'SETTLED', 'REJECTED', 'WITHDRAWN')),
    incident_date        DATE          NOT NULL,
    reported_at          TIMESTAMPTZ   NOT NULL,
    description          TEXT          NOT NULL,
    claimed_amount       NUMERIC(19,2) NOT NULL CHECK (claimed_amount >= 0),
    -- line-specific fields (motor: vehicle reg, third party...; property: address, peril...)
    -- validated by typed DTOs in the application; never queried across claims
    details              JSONB         NOT NULL DEFAULT '{}'::jsonb,
    team_id              UUID          NOT NULL REFERENCES team(id),
    assigned_handler_id  UUID          REFERENCES app_user(id),
    priority             VARCHAR(10)   NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    sla_due_at           TIMESTAMPTZ   NOT NULL,
    sla_paused_at        TIMESTAMPTZ,                           -- set while AWAITING_INFO
    submission_key       VARCHAR(100),                          -- Idempotency-Key from the client
    version              BIGINT        NOT NULL DEFAULT 0,      -- optimistic locking
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_claim_submission UNIQUE (claimant_id, submission_key)
);
CREATE INDEX ix_claim_team_status    ON claim(team_id, status);
CREATE INDEX ix_claim_handler_status ON claim(assigned_handler_id, status);
CREATE INDEX ix_claim_claimant       ON claim(claimant_id);

CREATE TABLE claim_status_history (
    id           UUID PRIMARY KEY,
    claim_id     UUID        NOT NULL REFERENCES claim(id),
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    actor_id     UUID        NOT NULL REFERENCES app_user(id),
    reason       TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_history_claim ON claim_status_history(claim_id, occurred_at);

CREATE TABLE information_request (
    id             UUID PRIMARY KEY,
    claim_id       UUID        NOT NULL REFERENCES claim(id),
    requested_by   UUID        NOT NULL REFERENCES app_user(id),
    question       TEXT        NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL,
    due_at         TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'ANSWERED', 'CANCELLED')),
    response_text  TEXT,
    responded_at   TIMESTAMPTZ
);
CREATE INDEX ix_inforeq_claim ON information_request(claim_id);

CREATE TABLE document (
    id                      UUID PRIMARY KEY,
    claim_id                UUID         NOT NULL REFERENCES claim(id),
    information_request_id  UUID         REFERENCES information_request(id),
    uploaded_by             UUID         NOT NULL REFERENCES app_user(id),
    file_name               VARCHAR(255) NOT NULL,
    content_type            VARCHAR(100) NOT NULL,
    size_bytes              BIGINT       NOT NULL,
    storage_key             VARCHAR(255) NOT NULL,
    uploaded_at             TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_document_claim ON document(claim_id);

-- Append-only exposure ledger. Current reserve = latest row per claim.
CREATE TABLE reserve_entry (
    id        UUID PRIMARY KEY,
    claim_id  UUID          NOT NULL REFERENCES claim(id),
    amount    NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    currency  CHAR(3)       NOT NULL,
    reason    TEXT          NOT NULL,
    set_by    UUID          NOT NULL REFERENCES app_user(id),
    set_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_reserve_claim ON reserve_entry(claim_id, set_at DESC);

CREATE TABLE decision (
    claim_id         UUID PRIMARY KEY REFERENCES claim(id),
    outcome          VARCHAR(20)   NOT NULL CHECK (outcome IN ('APPROVED', 'REJECTED')),
    approved_amount  NUMERIC(19,2),
    reason_code      VARCHAR(40)   NOT NULL,
    notes            TEXT,
    decided_by       UUID          NOT NULL REFERENCES app_user(id),
    decided_at       TIMESTAMPTZ   NOT NULL,
    CHECK (outcome <> 'APPROVED' OR approved_amount IS NOT NULL)
);

CREATE TABLE payment (
    id         UUID PRIMARY KEY,
    claim_id   UUID          NOT NULL REFERENCES claim(id),
    amount     NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency   CHAR(3)       NOT NULL,
    reference  VARCHAR(100)  NOT NULL,
    paid_at    TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_payment_claim ON payment(claim_id);

-- ---------------------------------------------------------------- messaging
-- Transactional outbox: written in the same transaction as the claim change,
-- published to Kafka by a relay. Solves the DB/Kafka dual-write problem.
CREATE TABLE outbox_event (
    id            UUID PRIMARY KEY,               -- becomes the eventId consumers dedupe on
    aggregate_id  UUID         NOT NULL,          -- claimId, used as the Kafka key
    event_type    VARCHAR(60)  NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_event(created_at) WHERE published_at IS NULL;

-- Built from Kafka by the notification consumer.
CREATE TABLE notification (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES app_user(id),
    claim_id    UUID         NOT NULL REFERENCES claim(id),
    type        VARCHAR(60)  NOT NULL,
    message     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    read_at     TIMESTAMPTZ
);
CREATE INDEX ix_notification_user ON notification(user_id, created_at DESC);

-- Consumer idempotency (at-least-once delivery means duplicates will happen).
CREATE TABLE processed_event (
    consumer      VARCHAR(60) NOT NULL,
    event_id      UUID        NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
