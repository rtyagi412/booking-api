# Movie Ticket Booking API

Spring Boot REST backend for movie ticket booking (Maven, base package `com.rahul.booking_api`, PostgreSQL). `pom.xml` is the source of truth for the stack and versions; `CLAUDE.md` holds the development conventions and build/test commands.

## Features

| Feature | Requirements | Status |
|---|---|---|
| Customer registration | [`features/customer-registration/intent.md`](features/customer-registration/intent.md) (design: `spec.md`, plan: `plan.md`, review: `REVIEW.md` in the same folder) | Implemented; review complete with deferred items (see `REVIEW.md`) |

Per-feature requirements and acceptance criteria live in `features/<feature>/intent.md`; they are not repeated here.

## Configuration and secrets

Configuration comes from environment variables; nothing secret is committed.

| Variable | Meaning | Local sample |
|---|---|---|
| `DB_HOST` / `DB_PORT` | PostgreSQL host and port | `localhost` / `5436` |
| `DB_NAME` | Database name | `booking` |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials | `booking` / `booking` |

- `application.yaml` requires all `DB_*` variables (no defaults) and uses `ddl-auto: validate`.
- Profile `local` (`application-local.yaml`) supplies the sample values above. Activate it with `SPRING_PROFILES_ACTIVE=local`.
- Tests use profile `test` and a Testcontainers PostgreSQL (Docker must be running); no `DB_*` variables are needed.
- Local setup: copy `.env.example` to `.env` (git-ignored), run `docker compose up -d` (it fails if `DB_PASSWORD` is unset), then start the app with the `local` profile (commands in `CLAUDE.md` section 9).
- Deployed environments must inject real `DB_*` values from the platform's secret store, never from files in the repository.

## Database migrations (Flyway)

The schema is managed only by versioned SQL migrations in `src/main/resources/db/migration/` (`V1__initial_schema.sql`, then `V2__...`). They run at application startup, and Hibernate only validates the result (`ddl-auto: validate`) in every profile. A new database gets all migrations automatically. Add a new `V<n>__description.sql` for every schema change and never edit one that has been applied. Check history with `docker exec booking-postgres psql -U booking -d booking -c "select version, description, type, success from flyway_schema_history"`.

A database created before Flyway (tables but no `flyway_schema_history`) is refused at startup by design and needs a one-time, explicitly approved baseline at version 1 after a verified backup. See `CLAUDE.md` section 4.

## Running with Docker

Requires `.env` (copy `.env.example`) and Docker Desktop.

```powershell
docker compose up -d                      # PostgreSQL only (for IDE / mvnw development)
docker compose --profile app up -d --build  # PostgreSQL + backend on http://127.0.0.1:8080
docker compose logs -f backend
docker compose --profile app down         # stop; the postgres-data volume is kept
```

Health checks: `GET /actuator/health/liveness` (process only) and `/actuator/health/readiness` (also checks PostgreSQL) return just `{"status":"UP"}` or `DOWN` (503). Compose marks the backend `healthy` from readiness. Verify a deployment without writing data:

```powershell
.\scripts\smoke-test.ps1   # read-only; exit code 0 means all checks passed
```

The image is a multi-stage build (Java 25, Maven wrapper) that runs as a non-root user and contains no credentials; the backend receives `DB_*` values at runtime. In Compose it reaches the database as `postgres:5432` and Flyway applies the schema migrations at startup.

## Unresolved product documentation

Project-level requirements are not yet written down. The customer-registration intent cites sections 2, 4.1, 5.1 and 6 of this README, but those sections have never existed in the repository. Their content (for example API path conventions, error format, role model) is **unresolved**: nothing is assumed, and anything depending on it is marked as proposed in the feature `spec.md`. The product owner has to supply these sections or confirm they never existed. See `features/customer-registration/REVIEW.md` §5.
