-- One Postgres instance for local convenience, but each service gets its own
-- login role and its own schema. Neither role can read the other's schema,
-- so the service boundary is enforced by the database, not just by convention.

CREATE ROLE claims_svc   LOGIN PASSWORD 'claims_svc';
CREATE ROLE insights_svc LOGIN PASSWORD 'insights_svc';

CREATE SCHEMA claims   AUTHORIZATION claims_svc;
CREATE SCHEMA insights AUTHORIZATION insights_svc;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA claims   FROM insights_svc;
REVOKE ALL ON SCHEMA insights FROM claims_svc;

ALTER ROLE claims_svc   SET search_path = claims;
ALTER ROLE insights_svc SET search_path = insights;
