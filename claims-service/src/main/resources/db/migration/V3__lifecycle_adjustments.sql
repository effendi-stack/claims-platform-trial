-- Adjustments made while building the claim lifecycle (V1/V2 are never edited once applied).

-- Currency as VARCHAR(3): maps cleanly to java.lang.String under Hibernate schema validation
-- (CHAR(3) is reported as bpchar and fails ddl-auto=validate).
ALTER TABLE market        ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE policy        ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE claim         ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE reserve_entry ALTER COLUMN currency TYPE VARCHAR(3);
ALTER TABLE payment       ALTER COLUMN currency TYPE VARCHAR(3);

-- Current reserve cached on the claim so queues and lists don't need a
-- "latest ledger row per claim" query. reserve_entry stays the audit trail.
ALTER TABLE claim ADD COLUMN current_reserve NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (current_reserve >= 0);

-- Reserve entries made by the system (the initial reserve rule) have no human author.
ALTER TABLE reserve_entry ALTER COLUMN set_by DROP NOT NULL;
