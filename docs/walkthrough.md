# Walkthrough Guide

Talking points, a demo script and likely panel questions. Design detail is in [architecture.md](architecture.md); how the AI was directed is in [ai-journal.md](ai-journal.md).

---

## 1. The 30-second version

- **Two Spring Boot services joined by Kafka, plus one Angular app.**
- **claims-service** is the system of record.
  - It runs the claim lifecycle with a state machine and a reserve ledger.
  - Every change writes a domain event through a transactional outbox.
- **insights-service** builds the workload and liability-exposure views from those events.
- **REST carries commands and queries; Kafka carries facts.**
- The browser gets "claim changed" hints over SSE and refetches over REST.
- **Priorities:** the backend lifecycle and exposure first, then the event flow, then a thin UI.

---

## 2. Prioritisation and time allocation

| Order | What | Why this order |
|---|---|---|
| 1 | Design doc before any code | The brief scores decisions, so I agreed them first. Section 13 of the architecture doc records what changed later. |
| 2 | Skeleton: compose, schema, seed data | Real Postgres and Kafka from minute one, so nothing is mocked. |
| 3 | Claim lifecycle and permissions, with tests | The core of the brief, for both user types. Built the most carefully. |
| 4 | Outbox → Kafka → notifications and insights | Async handling and exposure are what the brief asks about explicitly. |
| 5 | Angular frontend | Last and thin, as the brief advises. It renders what the API allows rather than re-implementing rules. |

**Deliberately left out:**
- document uploads
- the `PENDING_APPROVAL` referral state
- a real IdP
- live FX rates
- caching
- frontend tests

Each has a reason in section 14 of the architecture doc.

---

## 3. Demo script (about 8 minutes)

**Before the session:**
- Run `docker compose --profile app up --build` (wait until both services are healthy).
- Run `cd frontend && npm start`.
- Open http://localhost:4200 in **two browser tabs**.

1. **Login page.** Seeded users across three roles. Each tab is its own session.
2. **Tab A, claimant Mei Tan:**
   - Go to *Report a claim*, pick the motor policy and submit.
   - Point out:
     - The form changes by product line.
     - An `Idempotency-Key` makes double-submits safe.
     - The claimant sees plain-language statuses and **no reserve**.
3. **Tab B, handler Priya Nair:**
   - Open *Queue → Unassigned*. Point out:
     - The summary strip comes from insights.
     - The queue is sorted by decision deadline.
     - The initial reserve is `min(claimed, sum insured) − deductible`.
   - Open the claim and **pick it up**. Point out:
     - The action panel shows only what the API returns in `allowedActions`.
     - If Daniel tries to pick it up too, he gets a 409. This is shown in the integration test.
   - **Request information.**
4. **Tab A updates by itself:**
   - The status becomes "Action needed", the answer form appears, and the notification count goes up.
   - That's SSE.
   - Answer the question.
5. **Tab B updates by itself:** the claim is back in review.
   - **Revise the reserve:** the reserve history shows who, when and why.
   - **Approve 25,000:** refused, because it's above Priya's 20,000 authority limit.
   - **Approve 6,000**, then **record the payment**. The reserve goes to 0.
6. **Sign in as manager Grace Ong:**
   - The **dashboard** shows outstanding liability by market and line in each currency, the USD equivalent, the largest open reserves, and handler workload and throughput.
7. **Kafka UI** (http://localhost:8090):
   - Show `claims.events` keyed by claimId with `eventType` headers.
   - Show the DLT topic.
8. **Optional resilience demo:**
   - `docker compose stop insights-service`, then approve a claim: claims still work.
   - `docker compose start insights-service`: the dashboard catches up from its consumer offset.

---

## 4. Likely questions

**Why two services and not one, or more than two?**
- **Why split at all:** the write side needs strong consistency and validation while the user waits. The analytics side does heavy aggregates and can lag by a second. That difference is what drives the split.
- **Why not split further:** intake, assessment and settlement all mutate the *same* aggregate. Splitting them would mean sagas and distributed state for no gain at one team's scale.
- **What enforces the boundary:**
  - Separate Postgres roles per schema.
  - The services share no code.
  - claims-service never calls insights-service.

**What goes over REST and what over Kafka?**
- Anything a user waits on is REST: commands, because they need immediate validation errors, and queries.
- Facts that several consumers react to independently go on Kafka:
  - insights projections
  - notifications
  - live UI hints
  - future consumers such as finance or fraud
- The browser never touches Kafka.

**Why an outbox?**
- Writing to the DB and Kafka separately (a dual write) can lose events or publish events for changes that rolled back.
- The outbox row commits with the claim change; a relay publishes it afterwards.
- That gives at-least-once delivery, so every consumer dedupes on eventId.

**How do you guarantee ordering?**
- The topic is keyed by claimId, so all of a claim's events land on one partition, in order.
- The relay sends sequentially.
- Insights also ignores any snapshot whose version is not newer, so even a replay or a duplicate can't regress state.

**Why put a full snapshot in each event, rather than just the delta?**
- Consumers can upsert without calling back to claims-service, and without the risk of a missed delta corrupting a total.
- Rebuilding a read model is just a replay from offset 0.
- The cost is larger messages.

**What happens if Kafka is down?**
- Commands still succeed, because events wait in the outbox table.
- The relay retries every 500 ms and drains the backlog when Kafka returns.
- Dashboards and notifications lag; nothing is lost.

**What happens if insights-service is down?**
- Claims are unaffected.
- When it comes back, it resumes from its committed offset.
- A poison message goes to `claims.events.insights.DLT` after retries, so it can't block its partition.

**How does the frontend handle async?**
- Commands return the claim's new state synchronously.
- Everything downstream is eventually consistent.
- SSE pushes "claim X changed", and the screen refetches over REST, so a missed push only costs freshness, never correctness.
- EventSource can't send headers, so the stream alone accepts `?access_token=`.

**What does a claims officer need to manage their workload?**
- Their own queue, sorted by decision deadline.
- The team's unassigned claims.
- SLA breach flags, which pause while the claimant owes information.
- Ageing buckets.
- Per-handler open and decided counts with average time to decision.

**What does the system need to know about liability exposure?**
- Per claim: a reserve ledger (amount, who, when, why), not just the claimed amount.
- Outstanding = reserves on unpaid claims, including approved-but-unpaid.
- Incurred = paid + outstanding.
- Always per currency. The USD total is a labelled convenience using static rates.

**Who defines the contract?**
- The backend, code-first.
- The specs are committed, and the Angular client is generated from them.
- Generation caught two real contract bugs (see the journal).

**How is it tested?**
- **Unit tests:** state machine, SLA pause and resume, reserve rule.
- **Integration tests against real Postgres and Kafka (Testcontainers):**
  - the full lifecycle over HTTP
  - authorisation: 404 for invisible claims, 403 for the wrong role
  - the double-pickup 409
  - idempotent submission
  - outbox ordering and versions
  - relay → Kafka → notification
  - insights duplicate and stale handling, aggregates, team scoping, and the DLT
- **No frontend tests:** a stated gap.

**What would you do with another day?** Section 14 of the architecture doc. First on the list: document uploads, the manager-approval referral, a contract CI check, and IdP integration.

---

## 5. Where things are in the code

| Topic | File |
|---|---|
| State machine | `claims-service/.../claim/domain/ClaimStatus.java` |
| Aggregate (SLA pause, reserve on approve and settle) | `claims-service/.../claim/domain/Claim.java` |
| Who can see and do what | `claims-service/.../claim/domain/ClaimPermissions.java` |
| Use cases plus outbox writes | `claims-service/.../claim/application/ClaimCommandService.java` |
| Outbox writer and relay | `claims-service/.../outbox/OutboxWriter.java`, `OutboxRelay.java` |
| Notifications and SSE | `claims-service/.../notification/` |
| Schema and seed data | `claims-service/src/main/resources/db/migration/` |
| Projection with dedupe and version guard | `insights-service/.../projection/ClaimSummaryProjector.java` |
| Exposure and workload SQL | `insights-service/.../api/InsightsQueries.java` |
| DLT and retry policy | `insights-service/.../config/KafkaConfig.java` |
| Claim screen driven by `allowedActions` | `frontend/src/app/pages/shared/claim-detail.page.ts` |
| Live updates | `frontend/src/app/core/live-updates.service.ts` |
| Key tests | `ClaimLifecycleIntegrationTest`, `EventFlowIntegrationTest`, `InsightsProjectionIntegrationTest` |
