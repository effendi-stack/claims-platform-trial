# Claims Platform (APAC motor & property)

A claims platform for claimants and claims staff across six APAC markets.

- **claims-service** (`:8081`): the system of record. It handles the claim lifecycle, information requests, reserves, decisions and notifications, and publishes domain events via a transactional outbox.
- **insights-service** (`:8082`): the read side. It builds workload and liability-exposure projections from `claims.events`.
- **frontend** (Angular): one app with claimant and staff areas. *Not started yet.*

Design and rationale: [docs/architecture.md](docs/architecture.md) · AI process log: [docs/ai-journal.md](docs/ai-journal.md)

## Prerequisites
- Docker Desktop (running)
- JDK 17+ (only needed to run the services outside Docker)

## Run

Everything in Docker:

```bash
docker compose --profile app up --build
```

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
| Kafka UI | http://localhost:8090 |
| Postgres | `localhost:5432/claims_platform`. Service roles are `claims_svc` and `insights_svc`; `admin`/`admin` is for inspection only. |

## Test

Integration tests use Testcontainers, so Docker must be running.

```bash
(cd claims-service && ./mvnw verify)
```

```bash
(cd insights-service && ./mvnw verify)
```
