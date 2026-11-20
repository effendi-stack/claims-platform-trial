# Claims Platform

This is my take-home submission: a claims platform for motor and property insurance across six APAC markets.

Today, claimants phone or email and then hear nothing. Claims staff juggle shared inboxes and spreadsheets, and managers can't see how much money is tied up in open claims. This project is my attempt to fix that.

- Claimants can report an incident, follow its progress, answer questions, and see the decision.
- Claims staff get a proper queue: they can pick up claims, assess them, and take them through to payment or rejection.
- Managers get a live view of workload and outstanding liability.

Everyone uses the same Angular app, and it shows each person what's relevant to their role.

Behind the app there are two Spring Boot services:

- **claims-service** (port 8081) is where claims actually live. It runs the lifecycle, enforces who can do what, and announces every change as an event on Kafka.
- **insights-service** (port 8082) listens to those events and works out the numbers managers and team leads need.

---

## Where to start reading

If you're reviewing this, here's the order I'd suggest.

1. **This README.** It covers what I built, what I left out, and how to run it.
2. **[docs/architecture.md](docs/architecture.md).** This is how I designed it and why. If you're short on time, sections 1 to 3 cover the big decisions: how I framed the problem, why I ended up with two services, and what goes over REST versus Kafka. The rest covers the claim lifecycle, how I model liability, the data model, the API contract, and a log of every decision, including the ones I changed my mind on.
3. **[docs/walkthrough.md](docs/walkthrough.md).** This is the demo I'll run, the answers I'd give to the questions I expect, and a quick map of where the important code lives.
4. **[docs/ai-journal.md](docs/ai-journal.md).** This is my running log of working with the AI: what I asked for, what I accepted, where I pushed back, and the mistakes I caught along the way.

The commit history is worth a look too. Each commit is one step, in the order I built things.

---

## What I built, and what I didn't

**What works:**

- **The full claim lifecycle.** Report, pick up or assign, ask the claimant for information, revise the reserve, approve, reject, record payment, or withdraw. Handlers have an approval limit, and anything above it needs the manager.
- **Permissions enforced on the server.** Claimants only ever see their own claims, and handlers see their own team's. Every claim response tells the frontend exactly which actions the current user may take.
- **Reliable events.** Every change is saved together with its event (the outbox pattern), then published to Kafka. From there, the events drive notifications, live updates in the browser, and the insights figures.
- **Workload and liability views.** These show who's working on what, what's overdue, how fast decisions are being made, and how much is outstanding, broken down by market, product line and currency.
- **An Angular UI for all three roles.** It updates live when someone else changes a claim.
- **Tests against real infrastructure.** 24 tests in claims-service and 5 in insights-service run against real Postgres and Kafka (via Testcontainers), not mocks.

**What I deliberately left out**, with more detail in section 12 of the architecture doc:

- document uploads
- automatic routing of over-limit approvals to the manager
- a real login provider (a dev-only login stands in)
- live exchange rates (the USD totals use a fixed table)
- caching
- frontend tests

---

## Running it

You'll need:

- **Docker Desktop**, running.
- **Node 20.19+ or 22.12+**, for the frontend.
- **Java 17+**, only if you want to run the services or tests outside Docker.

### 1. Start the backend

This one command starts Postgres, Kafka, a Kafka UI and both services:

```bash
docker compose --profile app up --build
```

The first build downloads all the Java dependencies, so give it a few minutes. After that it's quick.

### 2. Start the frontend

In another terminal:

```bash
cd frontend && npm install && npm start
```

Then open http://localhost:4200 and pick one of the demo users.

Each browser tab keeps its own login. My favourite way to try it is to open two tabs: be the claimant (Mei Tan) in one and the handler (Priya Nair) in the other. When Priya asks Mei a question, Mei's screen updates on its own.

### Other things you can open

| What | Where |
|---|---|
| claims-service API docs (Swagger) | http://localhost:8081/swagger-ui.html |
| insights-service API docs | http://localhost:8082/swagger-ui.html |
| Kafka UI, to watch events arrive | http://localhost:8090 |
| Postgres | `localhost:5432/claims_platform` (`admin` / `admin` to look around) |

### Running the services outside Docker

If you'd rather run the services from your IDE or terminal, start just the infrastructure:

```bash
docker compose up -d
```

Then run each service:

```bash
(cd claims-service && ./mvnw spring-boot:run)
```

```bash
(cd insights-service && ./mvnw spring-boot:run)
```

---

## Trying the API directly

There's no real login provider, so a dev-only endpoint lets you sign in as any of the seeded users:

- Mei Tan, a claimant: `mei.tan@example.com`
- Priya Nair and Daniel Lim, Singapore handlers: `priya.nair@example.com`, `daniel.lim@example.com`
- Grace Ong, the manager: `grace.ong@example.com`

```bash
curl -s -X POST localhost:8081/api/auth/dev-login -H 'Content-Type: application/json' -d '{"email":"mei.tan@example.com"}'
```

Copy the token from the response and send it as `Authorization: Bearer <token>`. Or paste it into the **Authorize** button in Swagger, which is the easiest way to explore.

The main places to look are `/api/claims` (with actions like `/api/claims/{id}/approve`), `/api/notifications` and, in insights, `/api/insights/workload` and `/api/insights/exposure`.

---

## Running the tests

The integration tests start their own Postgres and Kafka in Docker, so Docker needs to be running.

```bash
(cd claims-service && ./mvnw verify)
```

```bash
(cd insights-service && ./mvnw verify)
```

---

## A note for anyone changing the API

The frontend's API client is generated from the backends' OpenAPI specs, which are committed in `frontend/openapi/`. If you change a backend endpoint, start both services and regenerate the client:

```bash
cd frontend && npm run api:export && npm run api:generate
```

If the frontend no longer matches, it will fail to compile rather than break quietly at runtime.

---

By Muhamad Effendi
