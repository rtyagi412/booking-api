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
- Profile `local` (`application-local.yaml`) supplies the sample values above and `ddl-auto: update`. Activate it with `SPRING_PROFILES_ACTIVE=local`.
- Tests use profile `test` and a Testcontainers PostgreSQL (Docker must be running); no `DB_*` variables are needed.
- Local setup: copy `.env.example` to `.env` (git-ignored), run `docker compose up -d` (it fails if `DB_PASSWORD` is unset), then start the app with the `local` profile (commands in `CLAUDE.md` section 9).
- Deployed environments must inject real `DB_*` values from the platform's secret store, never from files in the repository.

## Unresolved product documentation

Project-level requirements are not yet written down. The customer-registration intent cites sections 2, 4.1, 5.1 and 6 of this README, but those sections have never existed in the repository. Their content (for example API path conventions, error format, role model) is **unresolved**: nothing is assumed, and anything depending on it is marked as proposed in the feature `spec.md`. The product owner has to supply these sections or confirm they never existed. See `features/customer-registration/REVIEW.md` §5.
