# Claims Platform (APAC motor & property)

A claims platform for claimants and claims staff across six APAC markets.

- **claims-service** (`:8081`): the system of record. It handles the claim lifecycle, information requests, reserves, decisions and notifications, and publishes domain events via a transactional outbox.
- **insights-service** (`:8082`): the read side. It builds workload and liability-exposure projections from `claims.events`.
- **frontend** (Angular 20): one app with claimant, handler and manager areas, a client generated from both services' OpenAPI specs, and live updates over SSE.

## Documentation

| Doc | What it covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Service boundaries, REST vs Kafka, data model, exposure model, decisions log, known limitations |
| [docs/walkthrough.md](docs/walkthrough.md) | Demo script, likely questions with answers, where things live in the code |
| [docs/ai-journal.md](docs/ai-journal.md) | What I asked the AI, what I accepted, challenged and overrode |

## What's built

| Built | Not built (deliberately) |
|---|---|
| Claim lifecycle: submit, pick up or assign, request info, respond, reserve, approve (authority limit), reject, settle, withdraw | Document uploads |
| Role-based visibility and actions, enforced server-side (`allowedActions`) | `PENDING_APPROVAL` referral to a manager |
| Transactional outbox → Kafka → notifications, SSE live updates, insights projection | Real IdP (a dev login stands in) |
| Workload, throughput and liability exposure by market × line × currency | Live FX rates (static table), caching |
| Angular UI for claimant, handler and manager | Frontend unit tests |
| Integration tests on real Postgres and Kafka (Testcontainers): 24 in claims-service, 5 in insights-service | |

## Prerequisites
- Docker Desktop (running)
- Node 20.19+ or 22.12+ for the frontend
- JDK 17+, only needed to run the services or tests outside Docker

## Run

Backend and infrastructure in Docker. The first build downloads Maven dependencies, so allow a few minutes.

```bash
docker compose --profile app up --build
```

Then start the frontend (see [Frontend](#frontend)).

Infrastructure only, then run the services locally:

```bash
docker compose up -d
```

```bash
(cd claims-service && ./mvnw spring-boot:run)
```

```bash
(cd insights-service && ./mvnw spring-boot:run)
```

| What | URL |
|---|---|
| claims-service API docs | http://localhost:8081/swagger-ui.html |
| insights-service API docs | http://localhost:8082/swagger-ui.html |
| Kafka UI | http://localhost:8090. Topics: `claims.events`, `claims.events.insights.DLT` |
| Postgres | `localhost:5432/claims_platform`. Service roles are `claims_svc` and `insights_svc`; `admin`/`admin` is for inspection only. |

## Frontend

Requires Node 20.19+ or 22.12+. Run both services first.

```bash
cd frontend && npm install && npm start
```

Open http://localhost:4200 and pick a demo user. The session is per browser tab, so open a second tab to act as the handler while the first is the claimant, and watch changes arrive live.

The dev proxy (`frontend/proxy.conf.json`) routes `/api/insights/**` to `:8082` and the rest of `/api/**` to `:8081`. To regenerate the typed API client after a backend change, with both services running:

```bash
cd frontend && npm run api:export && npm run api:generate
```

## Try it with curl

Log in as a seeded user. Users are listed at `GET :8081/api/auth/demo-users`: claimant `mei.tan@example.com`, SG handlers `priya.nair@example.com` and `daniel.lim@example.com`, manager `grace.ong@example.com`.

```bash
curl -s -X POST localhost:8081/api/auth/dev-login -H 'Content-Type: application/json' -d '{"email":"mei.tan@example.com"}'
```

Then call the APIs with `Authorization: Bearer <token>`, or use the Swagger UI's *Authorize* button.

| Service | Main endpoints |
|---|---|
| claims-service | `/api/claims`, `/api/claims/{id}` with action sub-resources, `/api/notifications`, `/api/events/stream` (SSE) |
| insights-service | `/api/insights/workload`, `/api/insights/throughput`, `/api/insights/exposure` (manager only) |

## Test

Integration tests use Testcontainers, so Docker must be running.

```bash
(cd claims-service && ./mvnw verify)
```

```bash
(cd insights-service && ./mvnw verify)
```
