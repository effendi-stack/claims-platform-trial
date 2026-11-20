-- insights-service schema. Everything here is derived from claims.events and can be
-- rebuilt by truncating these tables and replaying the topic from the beginning.

-- One row per claim, upserted from events. All workload and exposure figures
-- are GROUP BY queries over this table.
CREATE TABLE claim_summary (
    claim_id             UUID PRIMARY KEY,
    claim_number         VARCHAR(30)   NOT NULL,
    market_code          VARCHAR(2)    NOT NULL,
    product_line         VARCHAR(20)   NOT NULL,
    currency             CHAR(3)       NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    team_id              UUID          NOT NULL,
    assigned_handler_id  UUID,
    current_reserve      NUMERIC(19,2) NOT NULL DEFAULT 0,
    paid_amount          NUMERIC(19,2) NOT NULL DEFAULT 0,
    reported_at          TIMESTAMPTZ   NOT NULL,
    decided_at           TIMESTAMPTZ,
    sla_due_at           TIMESTAMPTZ,
    -- version of the last applied claim event: lets us ignore stale/out-of-order updates
    last_event_version   BIGINT        NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_summary_segment ON claim_summary(market_code, product_line, status);
CREATE INDEX ix_summary_team    ON claim_summary(team_id, status);
CREATE INDEX ix_summary_handler ON claim_summary(assigned_handler_id, status);

-- Static, illustrative rates for the USD reporting total. A real system would use a rates feed.
CREATE TABLE fx_rate (
    currency    CHAR(3) PRIMARY KEY,
    usd_rate    NUMERIC(18,8) NOT NULL,
    as_of       DATE          NOT NULL
);
INSERT INTO fx_rate (currency, usd_rate, as_of) VALUES
    ('USD', 1.00000000, '2026-11-01'),
    ('SGD', 0.75000000, '2026-11-01'),
    ('HKD', 0.12800000, '2026-11-01'),
    ('AUD', 0.65000000, '2026-11-01'),
    ('MYR', 0.22000000, '2026-11-01'),
    ('THB', 0.02900000, '2026-11-01'),
    ('IDR', 0.00006200, '2026-11-01');

-- Consumer idempotency: Kafka delivery is at-least-once.
CREATE TABLE processed_event (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL
);
