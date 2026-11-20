# AI Working Journal

Running log of how AI (Claude Code, Opus 5.5) was directed: what I asked, what I accepted, what I challenged and what I overrode.
Unpolished by design. Newest entries at the bottom.

> Draft entries were written by the AI from the session transcript. I review and rewrite them in my own words; anything marked **[review]** has not been signed off yet.

---

## 2026-11-20 — Understanding the brief
- **Asked:** read the brief and restate it before doing anything; explicitly *no development yet*.
- **Accepted:** the AI's summary of the core decisions the brief is really testing: service boundaries, REST vs Kafka, the FE/BE contract, async handling, workload views and liability exposure.
- **Why I held back on code:** the brief says the decisions are what is being assessed, so I wanted the design agreed before any code was generated.

## 2026-11-20 — Architecture and data model
- **Asked:** propose the architecture and data model, with alternatives, before any code.
- **Accepted:**
  - REST for commands and queries, Kafka for domain events (facts).
  - A transactional outbox to avoid the DB/Kafka dual-write problem.
  - An append-only reserve ledger as the basis for liability exposure.
  - JSONB for the motor/property-specific fields.
  - SSE for live UI updates.
  - Optimistic locking and an Idempotency-Key on submission.
- **Decided (my call):** Spring Boot rather than .NET.
- **Challenged → AI reversed:** the AI's first proposal was a single modular monolith. When I said I wasn't sure which approach fit the brief best, it re-read the brief's question *"Is the backend one service or more than one? What drives that decision?"* and switched to recommending two services. With one service, the use of Kafka is hard to justify ("why use Kafka inside one process?"). With claims (write side) and insights (read side), Kafka has a real job, and the split is driven by different consistency and load needs. I agreed. **[review]**
- **Accepted defaults:** initial reserve = min(claimed, sum insured) − deductible; authority-limit approval is a stretch goal.

## 2026-11-20 — Skeleton
- **Adjusted:** Java 21 → Java 17, because that is the installed JDK and Spring Boot 3.5 supports it.
- **AI-initiated changes to check:** **[review]**
  - Dropped the pre-aggregated `exposure_by_segment` table. Exposure is now a GROUP BY over `claim_summary`, which avoids error-prone incremental +/- bookkeeping.
  - insights-service uses JDBC rather than JPA.
  - One Postgres login role per service, so the schema boundary is enforced by the database.
- **Verified:** both services compile.

## 2026-11-20 — Claim lifecycle (claims-service)
- **Asked:** implement the claim lifecycle in claims-service: aggregate, state machine, REST commands and queries, outbox writes, tests.
- **AI design choices to check:** **[review]**
  - **Permissions:** a single `ClaimPermissions` class decides both enforcement and the UI's `allowedActions`, so the rules can't drift apart. A claim the caller cannot see returns 404 rather than 403.
  - **Event shape:** events carry a full claim snapshot plus the claim `version` ("event-carried state transfer"), so insights never calls back to claims-service.
  - **Hidden from claimants:** the reserve, SLA and reserve history are omitted from claimant responses.
  - **Authority limit:** implemented as a 422 refusal; the manager approves above the limit. A `PENDING_APPROVAL` referral state is still a stretch goal.
  - **Dev login:** a password-less endpoint issues HS256 JWTs for seeded users. It is clearly marked dev-only and switched off by a config property.
  - **Migration discipline:** schema changes went into a new V3 migration rather than editing V1, which had already been applied.
- **AI mistakes caught during the work:**
  - Authority-limit check: the first version treated a missing limit as zero, which would have blocked every approval. Fixed so that a null limit means unlimited.
  - `ClaimIntake` had a leftover dummy method; removed.
  - An integration-test assertion failed on a list comparison. The API response was correct; the assertion style was swapped for Hamcrest `contains`.
- **Verified:**
  - 23 tests pass: unit tests for the state machine, SLA pause/resume and the reserve rule, plus integration tests on real Postgres via Testcontainers.
  - The integration tests cover the full lifecycle, the double-pickup 409, visibility 404s, the claimant-cannot-approve 403, idempotent submission, validation, and ordered outbox events with increasing versions.
  - V3 migrated cleanly on the Docker database using the service's own login role.
