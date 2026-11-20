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
