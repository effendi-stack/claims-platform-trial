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

## 2026-11-20 — Kafka flow
- **Asked:** build the outbox relay, the notification and SSE consumers, and insights-service end to end.
- **AI design choices to check:** **[review]**
  - **Relay:** polls with `FOR UPDATE SKIP LOCKED` and waits for the broker ack before marking a row published, giving at-least-once delivery.
  - **Consumer idempotency:** both consumers dedupe on eventId. Insights also ignores snapshots that are not newer, by version.
  - **Dead letters:** insights sends poison messages to its own DLT after retries, so they can't block a partition. Notifications just log and skip.
  - **Notification timing:** notifications run from Kafka rather than inside the command transaction, so they can never roll back a decision. The SSE push fires only after commit.
  - **SSE token:** `?access_token=` is accepted on the SSE endpoint only, because EventSource can't set headers.
  - **Tolerant reader:** insights has its own event model rather than a shared library.
  - **Exposure:** APPROVED counts as outstanding until it is paid. Paused claims don't count as SLA breaches (a stated simplification).
- **AI mistake caught:** the first test consumers read `spring.kafka.bootstrap-servers` via `@Value`. With `@ServiceConnection` that property isn't set, so the tests silently pointed at the Docker Compose broker and timed out. Fixed by using the app's own `ConsumerFactory`.
- **Verified:**
  - Tests: claims-service 24 and insights-service 5 pass, against real Kafka and Postgres containers.
  - The insights tests cover out-of-order and duplicate events, the exposure and workload maths, team scoping, and the DLT path.
  - End to end on Docker: a claim submitted, picked up and approved over HTTP produced 3 SSE pushes and 3 notifications for the claimant, and showed up in the insights workload and exposure. No outbox rows were left unpublished.

## 2026-11-20 — Angular frontend
- **Asked:** build a thin frontend: claimant (report, track, respond), handler (queue, claim detail with actions), and the manager dashboard if time allows.
- **Decisions:** **[review]**
  - **Angular version:** Angular 20 via `npx @angular/cli@20`, because the global CLI was v14 and doesn't support Node 22.
  - **Contract:** the typed client is generated from both services' committed OpenAPI specs (`ng-openapi-gen`), so the backend owns the contract.
  - **Permissions:** screens render the API's `allowedActions` rather than re-implementing the rules. Route guards are for UX only.
  - **Sessions:** stored in `sessionStorage`, so each browser tab is its own user. That makes the demo work as claimant and handler side by side.
  - **Live updates:** a single EventSource per tab. A push only triggers a REST refetch.
- **Generation exposed two backend contract bugs, fixed in the backend rather than patched around:**
  - springdoc declared responses as `*/*`, so the generated client read every body as a `Blob`. Fixed with `springdoc.default-produces-media-type: application/json`.
  - `Pageable` was documented as one `pageable` object instead of `page`, `size` and `sort`. Fixed with `@ParameterObject`.
  - Also added `teamId` to the staff claim summary, so the manager's "assign to" list can load handlers.
- **Caught in browser testing:** the claim-details panel title-cased free text (`SGX1234A` became `Sgx1234a`). Fixed so that only enum codes are reformatted.
- **Verified in the browser:**
  - Claimant: reported a claim through the form; the notification arrived live.
  - Handler (in a second tab): saw it in the unassigned queue, picked it up and requested information. The claimant's tab switched to "Action needed" with the response form, with no reload.
  - Manager: the dashboard showed exposure by market and line, the USD totals and handler workload. No console errors.
- **Not built:** document uploads, frontend unit tests, and serving the frontend from Docker Compose.
