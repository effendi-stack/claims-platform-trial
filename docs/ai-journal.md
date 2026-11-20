# AI Working Journal

This is my log of how I worked with the AI on this project. It covers what I asked it to do, what I went along with, where I pushed back, and what it got wrong. I used Claude Code (Claude Opus) as my main tool for the whole sprint. It wrote most of the code, but I decided what got built, in what order, and when something was good enough.

I drafted these entries from our session as I went, so they're a bit rough in places. That's intentional. Newest entries are at the bottom.

---

## The short version

If you only read one part of this file, read this.

**The calls I made myself:**
- No code until we'd agreed a design.
- Spring Boot rather than .NET.
- Backend first, with the frontend kept thin and built last.
- Accepting the AI's proposed rule for the initial reserve, and parking the "send over-limit approvals to the manager" feature as a stretch goal.

**Where the direction changed because I questioned it:**
- The AI's first design was a single service. When I asked which approach actually fit the brief, it went back to the brief's own question, *"What drives that decision?"*, and changed its recommendation to two services. I agreed with the reasoning.
- I also pushed back on how the documentation read. The first versions were accurate but sounded machine-generated: lots of bullets, no real explanation. I asked for the architecture doc, walkthrough, README and this journal to be rewritten so they explain the thinking the way I'd explain it out loud.

**Mistakes the AI made that were caught before they shipped:**
- It treated "no approval limit" as a limit of zero, which would have blocked every approval.
- It left a pointless dummy method in one class.
- Its Kafka test consumers were quietly connecting to the wrong Kafka instance, so the tests timed out.
- The UI was mangling free text. A registration like "SGX1234A" came out as "Sgx1234a".

**Problems found by checking, not by reading code:**
- Generating the frontend's API client exposed two real bugs in the API spec.
- Clicking through the app in a browser caught the text-formatting bug.
- Running the whole stack in Docker proved the "insights can go down and catch up" claim actually holds.

**Ground rules I held it to:**
- Never edit an old database migration; add a new one.
- Every change from the original design gets written down in the decisions log.
- Nothing counts as "working" until a test, a curl call or the browser shows it working.

---

## Understanding the brief

Before anything else, I gave the AI the brief and told it plainly: read it, explain it back, and don't start building.

Its summary picked out what I think the brief is really testing:
- where the service boundaries go
- what's REST and what's Kafka
- who owns the contract between frontend and backend
- how to handle claims being slow and asynchronous
- what staff need to manage their workload
- how to track liability

I agreed with that framing.

I held back on code because the brief says the decisions are a big part of the assessment. I didn't want to end up reverse-engineering a design from code the AI had already written.

---

## Designing the architecture and data model

I asked for the architecture and data model first, with alternatives and reasons, before any code.

**What I accepted:**
- REST for actions and screens, and Kafka for events other parts of the system react to.
- A transactional outbox, so saving a claim and announcing it can't get out of step.
- Tracking each claim's expected payout as a ledger (the "reserve"), rather than trusting the claimed amount.
- Storing the motor- and property-specific details in a JSON column.
- Server-Sent Events for live updates in the browser.
- Version numbers on claims to catch two people changing the same claim, and an idempotency key so a double-click can't create two claims.

**What I decided myself:**
- Spring Boot over .NET.

**Where I questioned it:**
- The AI's first proposal was one service split into internal modules, and it had a reasonable case. But when I said I wasn't sure which option best fit the brief, it re-read the question *"Is the backend one service or more than one? What drives that decision?"* and changed its mind.
- Its argument: with one service, it's hard to justify Kafka at all. Why put a message broker between two halves of the same process?
- With a claims service (the source of truth) and a separate insights service (analytics that can be a second behind), the split is driven by genuinely different needs, and Kafka has a real job.
- I agreed. I think the reversal is worth recording, because the first answer wasn't wrong, just a weaker fit for what the brief was asking.

**Defaults I accepted:**
- The starting reserve is the claimed amount, capped at the sum insured, minus the deductible.
- The manager-approval routing is a stretch goal.

---

## Setting up the skeleton

**A small adjustment:** the design said Java 21, but my machine has Java 17. Spring Boot 3 runs fine on 17, so we went with that rather than installing anything.

**Changes the AI made on its own,** which I looked at and kept:
- **It dropped a pre-calculated exposure table** from the design. Exposure is now worked out with a simple query over one table. Its reasoning was that keeping running totals right through every possible claim change is fiddly and easy to get wrong, and at this size the query is fast anyway. That made sense to me.
- **insights-service uses plain JDBC** instead of JPA, because it only does upserts and summary queries.
- **Each service gets its own database login** that can only see its own schema. I liked this one: it means the "services don't read each other's data" rule is enforced by Postgres, not just promised.

**Checked:** both services compiled and started, and their basic tests passed against real Postgres and Kafka.

---

## Building the claim lifecycle

I asked for the full claims lifecycle in claims-service: the claim itself, the rules for which status changes are allowed, the API, writing events to the outbox, and tests.

**Design choices the AI made that I'm happy to defend:**
- **One class decides who can see and do what** (`ClaimPermissions`). The same class enforces the rules and tells the frontend which buttons to show, so the two can't disagree.
- **A claim you're not allowed to see returns "not found"**, not "forbidden", so the API doesn't reveal whether a claim exists.
- **Every event carries a full copy of the claim's key fields** plus a version number. The insights service never has to ask claims-service for anything.
- **Claimants never see the reserve or the internal deadlines.**
- **The approval limit is enforced as a refusal for now.** Above the limit, the manager has to approve.
- **A dev-only login** lets you sign in as any seeded user, and a config setting switches it off.
- **When the schema needed changing, it added a new migration** rather than editing the first one, which had already run. I'd asked for that.

**What went wrong along the way:**
- **The approval-limit check had a real bug.** It treated "no limit set" as a limit of zero, which would have blocked every approval. It was caught and fixed so that no limit means unlimited.
- **It left a meaningless dummy method** in one class. Removed.
- **One test failed even though the API was right.** The way the test compared lists was the problem, so the assertion was rewritten.

**Checked:**
- 23 tests passing, including the full lifecycle end to end over HTTP.
- Two handlers grabbing the same claim correctly conflicts.
- People can't see or act on claims they shouldn't.
- Duplicate submissions are handled.
- Events come out in the right order.

---

## Getting events through Kafka

Next I asked for the whole event flow: the relay that publishes from the outbox, the notifications and live updates, and the insights service end to end.

**What it built:**
- **A relay that only marks an event as sent once Kafka confirms it.** Occasionally an event may go out twice, so both consumers ignore anything they've already seen. Insights also ignores out-of-date versions.
- **A dead-letter topic for insights.** If a message is broken, it's set aside after a few retries instead of blocking everything behind it. Notifications just log and move on, because a failed notification isn't worth holding up the queue.
- **Notifications run off Kafka**, not inside the claim transaction, so they can never undo a decision. The live push to the browser only fires once the change is committed.
- **One endpoint accepts the login token in the URL: the live-updates stream.** The browser's EventSource can't send headers, so there's no other way for it to authenticate. Every other endpoint is header-only.
- **Insights has its own definition of the event format** rather than sharing code with claims-service. The JSON is the contract.

**What went wrong:**
- **The new Kafka tests timed out.** It turned out the test code was reading the Kafka address from a setting that isn't filled in when the test framework starts its own Kafka. So the tests were silently talking to the Kafka in my Docker setup instead. Once that was understood, the fix was simple: use the app's own Kafka connection.

**Checked:**
- 24 tests in claims-service and 5 in insights-service, including late and duplicate events, the exposure maths, team restrictions, and the dead-letter path.
- A real run through Docker: submitting, picking up and approving a claim over HTTP produced the live updates and notifications, and showed up in the insights figures. No events were left unsent.

---

## Building the frontend

I asked for a thin Angular app: the claimant reports and tracks claims, the handler gets a queue and a claim screen with actions, and the manager gets a dashboard if there was time.

**Decisions:**
- **Angular 20, run through npx.** My globally installed Angular CLI is an old version that doesn't work with my Node version.
- **The API client is generated from the backends' API specs**, so the backend owns the contract.
- **Screens show whatever actions the API allows** rather than re-implementing the rules.
- **Each browser tab keeps its own login**, which makes it easy to demo the claimant and the handler side by side.
- **The live connection is only a hint to refresh.** The real data always comes from the API.

**What generating the client exposed:**
- **Every response came back as raw binary.** The API spec described responses as "any type", so the generated client didn't treat them as JSON. Fixed in the backend.
- **Paging was described wrongly.** The spec showed paging as one object, when the API actually expects separate page, size and sort values. Also fixed in the backend.
- **The manager's "assign to" list needed one more field.** I added the claim's team to the staff view so the list could load.

I'm glad these were fixed properly in the backend rather than worked around in the frontend.

**What I caught in the browser:** the claim details panel was "prettifying" free text, so a registration of SGX1234A showed as Sgx1234a. Fixed so that only internal codes get reformatted.

**Checked in the browser:**
1. As the claimant, I reported a claim and the notification arrived straight away.
2. In a second tab as the handler, I picked it up and asked a question.
3. The claimant's tab changed to "Action needed" with an answer box, without me reloading.
4. As the manager, the dashboard showed exposure by market and line, the USD totals and each handler's workload.

No errors in the browser console.

**Not built:** document uploads, frontend tests, and running the frontend inside Docker.

---

## Final checks and documentation

**Running everything in Docker.** I had the AI run the full stack exactly the way a reviewer would, with `docker compose --profile app up --build`, and then go through the demo against it. That included stopping the insights service, approving a claim, and starting insights again. Claims kept working while it was down, and when it came back it caught up and showed the right status and figures. That's the resilience claim from the design, actually shown working.

