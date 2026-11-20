# Walkthrough Guide

These are my notes for presenting the project: how I'd summarise it, how I spent the time, a demo I can run in about eight minutes, and how I'd answer the questions I expect. The full reasoning is in [architecture.md](architecture.md), and the story of how I worked with the AI is in [ai-journal.md](ai-journal.md).

---

## 1. The short version

It's two Spring Boot services and one Angular app.

**claims-service** is where claims actually live. It runs the lifecycle from report to payment, enforces who can do what, and keeps a proper ledger of how much we expect to pay on each claim. Every time a claim changes, it writes an event, and that event is guaranteed to reach Kafka.

**insights-service** listens to those events and builds the views managers and team leads need: who's overloaded, what's overdue, and how much liability is outstanding in each market.

The split between the two transports is simple. Anything a person waits on goes over REST, and anything other parts of the system need to know about goes over Kafka. The browser gets a small "this claim changed" message over a live connection and re-fetches what it needs, so everyone's screen stays current without polling.

---

## 2. How I spent the time

I started by writing the design down before touching any code, because the brief is clear that the decisions are what's being assessed. Then I built in this order:

1. **The skeleton:** Docker Compose with real Postgres and Kafka from the very first commit, the database schema, and seed data for six markets. I didn't want anything mocked.
2. **The claim lifecycle and permissions, with tests.** This is the heart of the brief for both claimants and staff, so it got the most care.
3. **The event flow:** getting events out reliably through the outbox, then notifications, live updates, and the insights service with its exposure and workload figures.
4. **The frontend, last and deliberately thin.** It doesn't re-implement any business rules. It shows whatever the API says the user is allowed to do.

What I chose not to build, so I'm not caught out:

- document uploads
- routing over-limit approvals to the manager
- a real login provider
- live exchange rates
- caching
- frontend tests

Each one has a reason and a place on the next-steps list in section 12 of the architecture doc.

---

## 3. Demo script (about 8 minutes)

**Before the session**

- Run `docker compose --profile app up --build` and wait until both services are healthy.
- Run `cd frontend && npm start`.
- Open http://localhost:4200 in two browser tabs. Each tab keeps its own login, which is what makes the two-person demo work.

**Tab A: the claimant, Mei Tan**

1. Show the login page: seeded users across all three roles.
2. Sign in as Mei and go to *Report a claim*.
3. Pick her motor policy and fill in the form. Point out that the form changes depending on whether the policy is motor or property.
4. Submit, and talk through what she sees:
   - Statuses are in plain language: "Received", not `SUBMITTED`.
   - There's no reserve anywhere on her screen. That's internal information.
   - Behind the scenes, an idempotency key stops a double-click creating two claims.

**Tab B: the handler, Priya Nair**

5. Open *Queue → Unassigned* and talk through it:
   - The summary strip across the top comes from the insights service.
   - The list is sorted by decision deadline.
   - The reserve already shows 6,300: the 6,800 claimed minus the 500 deductible.
6. Open the claim and pick it up. Explain that the buttons on the right come straight from the API's `allowedActions`, and that if another handler tried to pick it up at the same moment, they'd get a conflict error. That case is covered in the integration tests.
7. Ask Mei for more information, for example photos of the damage.

**Back to tab A, without refreshing**

8. Mei's claim has changed to "Action needed", the answer box has appeared, and her notification count has gone up. That's the live connection at work.
9. She answers the question.

**Back to tab B**

10. The claim is back in review. Revise the reserve and give a reason. The reserve history shows who changed it, when and why.
11. Try to approve 25,000. It's refused, because it's above Priya's 20,000 authority limit.
12. Approve 6,000 instead, then record the payment. The reserve drops to zero.

**The manager, Grace Ong**

13. Sign in as Grace. The dashboard shows:
    - outstanding liability by market, product line and currency
    - a USD equivalent for the headline number
    - the largest open reserves
    - each handler's workload and how quickly they're deciding

**Under the hood**

14. Open Kafka UI at http://localhost:8090. Show the `claims.events` topic, with each message keyed by claim ID and tagged with its event type, and the dead-letter topic next to it.

**Optional: the resilience demo**

15. Run `docker compose stop insights-service` and approve a claim. Claims carry on working.
16. Run `docker compose start insights-service`. The dashboard catches up on its own. I've tested this end to end in Docker.

---

## 4. Questions I expect, and how I'd answer them

**"Why two services? Why not one, or more?"**

> Because the two halves have genuinely different needs. Claims needs to be strongly consistent and give an immediate answer; analytics does heavy number-crunching and can be a second behind. That difference is what earns the split.
>
> I didn't go further and split intake, assessment and settlement, because they all change the same claim. Splitting them would mean one claim living in several databases, and I'd need sagas to keep them in step. That's a lot of moving parts for no real benefit.
>
> And the boundary is enforced, not just agreed: each service has its own database login and can't read the other's tables, they share no code, and claims never calls insights.

**"What goes over REST and what goes over Kafka?"**

> If a person is waiting for the answer, it's REST. That means every action and every screen of data, because people need to see errors straight away.
>
> If it's a fact that other parts of the system should react to, it's a Kafka event. Today that's insights, notifications and live updates. Tomorrow it could be finance or fraud, without touching the claims service.
>
> The browser never talks to Kafka.

**"Why the outbox?"**

> If you save to the database and then send to Kafka as two separate steps, a crash in between either loses the event or announces a change that never happened.
>
> With the outbox, the event is saved in the same transaction as the claim, and a relay publishes it afterwards. The price is that an event can occasionally arrive twice, so every consumer ignores ones it's already seen.

**"How do you keep events in order?"**

> Every event is keyed by claim ID, so all of one claim's events land on the same Kafka partition and arrive in order. The relay also sends them one at a time.
>
> On top of that, insights ignores any snapshot that's older than what it already has, so even a replay or a duplicate can't move a claim backwards.

**"Why send the whole claim in every event, rather than just what changed?"**

> It keeps the consumers simple and safe. Insights just stores the latest version of each claim, it never has to call back for missing details, and rebuilding everything is just a replay from the beginning. The cost is slightly bigger messages, which is fine.

**"What happens if Kafka goes down?"**

> Claims keep working. The events wait in the outbox table, and the relay sends them as soon as Kafka is back. Dashboards and notifications fall behind for a while, but nothing is lost.

**"And if insights goes down?"**

> Claims aren't affected at all. When insights comes back, it carries on from where it stopped. If a message is broken, it goes to a dead-letter topic after a few retries, so it can't block everything behind it. I've actually tested the stop-and-restart in Docker.

**"How does the frontend deal with things happening asynchronously?"**

> When you do something, the response comes back with the claim's new state, so your own screen is always right. Anything that happens elsewhere arrives as a small "this claim changed" message over a live connection, and the page re-fetches. If a message is ever missed, you just see slightly old data until the next refresh, never wrong data.

**"What does a claims officer need to see?"**

> Their own queue, sorted by deadline, and the claims nobody's picked up yet. Clear flags for anything overdue or urgent; the overdue clock pauses while we're waiting on the claimant, which is fairer. How long claims have been open. And for the team view, each person's open claims, how many they've decided recently, and how long decisions are taking.

**"What does the system need to know about liability?"**

> For every claim, a reserve: our best estimate of what we'll pay. It's kept as a ledger, so we know who changed it and why, rather than just using the claimed amount.
>
> Outstanding liability is the reserves on everything not yet paid, including approved claims we haven't paid yet. Incurred is paid plus outstanding.
>
> It's always reported per currency. The USD total is a convenience with a clear label.

**"Who owns the contract between frontend and backend?"**

> The backend. The OpenAPI spec is generated from the code and committed, and the Angular client is generated from that spec, so if the backend changes shape, the frontend won't compile. Generating it actually caught two real bugs in my spec.

**"How did you test it?"**

> Unit tests cover the state machine, the deadline pause and resume, and the reserve rule.
>
> The integration tests run against real Postgres and Kafka using Testcontainers. They cover:
> - the full lifecycle over HTTP
> - the permission rules, including 404s for claims you can't see and 403s for the wrong role
> - two handlers grabbing the same claim
> - duplicate submissions
> - events coming out in order
> - an event travelling from the relay through Kafka to a notification
> - insights coping with duplicates and late events, getting the sums right, keeping handlers to their own team, and the dead-letter path
>
> There are no frontend tests yet. That's a known gap.

**"How did you use AI?"**

> It wrote most of the code, but I set the order of work, made the key calls, and checked everything it produced by running tests, curl and the browser rather than trusting it.
>
> It made mistakes that I caught. One example: it first treated "no authority limit" as zero, which would have blocked every approval. The journal lists them all, along with the moments where I pushed it to change direction.

**"What would you do with another day?"**

> Document uploads first, then sending over-limit approvals to the manager's queue, a check that the API spec never drifts, and a real login provider. The full list is at the end of the architecture doc.

---

## 5. Where things live in the code

If someone asks to see how something works, these are the files to open:

| Topic | File |
|---|---|
| Which status changes are allowed | `claims-service/.../claim/domain/ClaimStatus.java` |
| The claim itself (deadline pause, reserve on approve/settle) | `claims-service/.../claim/domain/Claim.java` |
| Who can see and do what | `claims-service/.../claim/domain/ClaimPermissions.java` |
| Each action, plus writing its event | `claims-service/.../claim/application/ClaimCommandService.java` |
| Outbox writer and relay | `claims-service/.../outbox/OutboxWriter.java`, `OutboxRelay.java` |
| Notifications and live updates | `claims-service/.../notification/` |
| Database schema and seed data | `claims-service/src/main/resources/db/migration/` |
| Insights storing snapshots safely | `insights-service/.../projection/ClaimSummaryProjector.java` |
| Exposure and workload queries | `insights-service/.../api/InsightsQueries.java` |
| Retries and dead-letter handling | `insights-service/.../config/KafkaConfig.java` |
| The claim screen driven by `allowedActions` | `frontend/src/app/pages/shared/claim-detail.page.ts` |
| Live updates in the browser | `frontend/src/app/core/live-updates.service.ts` |
| The main tests | `ClaimLifecycleIntegrationTest`, `EventFlowIntegrationTest`, `InsightsProjectionIntegrationTest` |
