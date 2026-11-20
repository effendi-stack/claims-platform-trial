-- Fields added once the event snapshot was finalised in claims-service.
ALTER TABLE claim_summary ADD COLUMN assigned_handler_name VARCHAR(120);
ALTER TABLE claim_summary ADD COLUMN claimed_amount NUMERIC(19,2) NOT NULL DEFAULT 0;
-- true while the claim is waiting on the claimant: SLA clock stopped, not counted as breached
ALTER TABLE claim_summary ADD COLUMN sla_paused BOOLEAN NOT NULL DEFAULT false;
