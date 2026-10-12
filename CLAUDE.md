# CLAUDE.md

## 1. Project overview and source of truth
Movie Ticket Booking API: Spring Boot REST backend (Maven, base package `com.rahul.booking_api`).

Source of truth:
- `pom.xml` — actual stack and versions (Spring MVC, JPA, Security, Validation, PostgreSQL, Lombok). Do not assume other libraries.
- `src/main/resources/application.yaml`, `docker-compose.yml` — runtime configuration.
- `README.md` — root product requirements. Do not duplicate it here.
- `features/<feature>/intent.md` — per-feature requirements and acceptance criteria. Read the relevant one before implementing.

Known gap: `README.md` currently contains only the customer-registration intent. The sections that `features/customer-registration/intent.md` cites (2, 4.1, 5.1, 6) are missing. Do not invent them; ask the user.

## 2. Architecture and package conventions
- Feature-based packaging under `com.rahul.booking_api`: `customer`, `theatre`, `show`, `booking`, etc.
- Inside a feature package, add only the subpackages actually needed: `controller`, `service`, `repository`, `entity`, `dto`. No empty packages.
- Controllers handle HTTP and validation only; business rules live in services; persistence in Spring Data repositories.
- Never expose entities in API responses; use request/response DTOs.
- No extra abstractions, mapper libraries or patterns unless asked.

## 3. Java, Spring Boot and Lombok
- Verify an API exists in the Spring Boot/Java version in `pom.xml` before using it.
- Constructor injection only (no field `@Autowired`).
- Bean Validation on request DTOs; clear 4xx errors for invalid input.
- Lombok is configured. Prefer `@Getter`/`@Setter`/`@RequiredArgsConstructor`/`@Builder`; avoid `@Data` on JPA entities; consider records for DTOs.
- Small methods, descriptive names, no unused code.

## 4. PostgreSQL, JPA and migrations
- Configuration comes from environment variables `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`. `application.yaml` has no defaults and uses `ddl-auto: validate`; the app will not start without them.
- Profiles: `local` (`application-local.yaml`, dev defaults and `ddl-auto: update`, activate with `SPRING_PROFILES_ACTIVE=local`) and `test` (`src/test/resources/application-test.yaml`, `create-drop`; every `@SpringBootTest` class uses `@ActiveProfiles("test")` with Testcontainers `@ServiceConnection`).
- `docker-compose.yml` reads the same `DB_*` variables from `.env` (copy `.env.example`); `DB_PASSWORD` is required there. Keep `open-in-view: false`.
- Before production-oriented schema evolution, recommend Flyway with `ddl-auto: validate`. Do not add Flyway or change database configuration unless asked.
- Enforce integrity in the database (unique constraints, not-null, lengths), and handle constraint violations explicitly (e.g. concurrent duplicates).
- Use explicit `@Table`/`@Column` constraints; `@Transactional` on writing service methods.
- Validate storage limits against the normalised value that will actually be persisted (e.g. trimmed, lower-cased), not the submitted text; normalisation can lengthen it.

## 5. Development workflow
1. Inspect relevant code, `README.md` and the feature `intent.md` first.
2. Simple, low-risk changes: implement directly.
3. Complex or high-risk features (security, concurrency, data model changes): write `features/<feature>/spec.md` and `plan.md` and get approval before coding.
4. Implement only the requested scope; respect "Out of scope" in each intent. No drive-by refactors.
5. Verify with appropriate tests and report results honestly, including failures or skipped steps.

## 6. Testing
- JUnit 5 with the Spring Boot test starters in `pom.xml`.
- Database tests use Testcontainers PostgreSQL (`TestcontainersConfiguration`); Docker must be running. Do not substitute H2.
- Cover each acceptance criterion in the feature `intent.md`: happy path, validation failures, edge cases such as concurrency and normalisation.
- Mirror the main package structure under `src/test/java`.

## 7. Security and secrets
- Spring Security is on the classpath; explicitly permit any endpoint that must be public.
- Hash passwords with a Spring Security `PasswordEncoder`; never store, log or return plain passwords or hashes.
- Never hardcode or commit real secrets; use environment variables. Real values never go in `application*.yaml` or `docker-compose.yml`; `.env` is git-ignored, `.env.example` holds local sample values only. Local-dev defaults exist only in `application-local.yaml`.
- Do not log personal data (emails, passwords) beyond what is necessary.

## 8. Repository boundaries and permissions
- Inspect and modify source code only inside this repository; do not look at unrelated directories.
- Maven and Docker may use their normal caches and runtime resources (e.g. `~/.m2`, Docker images/volumes) when the user has authorized running them.
- Never commit or push without explicit instruction.
- Do not edit `pom.xml`, `application.yaml`, `docker-compose.yml` or existing intent files unless the task requires it; ask before adding dependencies.
- Do not edit generated/ignored content (`target/`, `.idea/`, `HELP.md`).
- Ask before destructive actions. Do not change Claude Code permission settings.

## 9. Build and test commands
Run from the repository root.

| Task | PowerShell | Git Bash |
|---|---|---|
| Compile | `.\mvnw.cmd compile` | `./mvnw compile` |
| All tests | `.\mvnw.cmd test` | `./mvnw test` |
| One test class | `.\mvnw.cmd test "-Dtest=BookingApiApplicationTests"` | `./mvnw test -Dtest=BookingApiApplicationTests` |
| Package | `.\mvnw.cmd package` | `./mvnw package` |
| Run app (DB must be up, `local` profile) | `$env:SPRING_PROFILES_ACTIVE="local"; .\mvnw.cmd spring-boot:run` | `SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run` |

Docker (same in both shells; Docker Desktop must be running):
- Start DB (needs `.env`, see `.env.example`): `docker compose up -d`
- Status: `docker compose ps`
- Full stack (backend image + DB): `docker compose --profile app up -d --build`; logs: `docker compose logs -f backend`
- Stop (keeps data): `docker compose --profile app down`. Never add `-v` without asking (deletes the DB volume).
- Plain `docker compose up -d` starts only PostgreSQL. The backend container gets `DB_HOST=postgres`, `DB_PORT=5432` and `ddl-auto=update` from `docker-compose.yml` (local Compose only); it is published on `127.0.0.1:8080`.
- After editing `docker-compose.yml` or `.env`, run `docker compose --profile app up -d` (recreates only changed services); `restart` does not apply config changes. Code or Dockerfile changes need `up -d --build backend`. PostgreSQL is published on `127.0.0.1:5436` only.
- Health: only `GET /actuator/health`, `/actuator/health/liveness` (no DB) and `/actuator/health/readiness` (includes DB) are public, status only; all other Actuator paths stay protected. The backend container healthcheck uses readiness. Read-only smoke test: `.\scripts\smoke-test.ps1` (exit code 0 = pass).
