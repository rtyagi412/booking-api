---
name: spring-boot-api
description: Implement or extend a REST API feature in the booking-api Spring Boot project (controller, service, repository, DTO, entity, validation, error handling, tests). Use for any new endpoint or feature under com.rahul.booking_api.
---

# spring-boot-api

Workflow for building REST API features in this repository. `CLAUDE.md` and `pom.xml` remain the source of truth; if this skill conflicts with them, follow them and report the conflict.

## 1. Read before coding
1. `CLAUDE.md`, then `features/<feature>/intent.md`, `spec.md`, `plan.md` (whichever exist). Note acceptance criteria and "Out of scope".
2. `pom.xml` for the actual stack (currently Spring Boot 4.1.x, Java 25, Spring MVC/JPA/Security/Validation, PostgreSQL, Lombok, Testcontainers). Verify an API exists in that version before using it; add no dependencies without asking.
3. If intent/README cites sections that do not exist, or requirements are ambiguous, ask the user; do not invent them.
4. Complex or high-risk work (security, concurrency, data model) needs `spec.md` + `plan.md` approved before any code. Without approval, stop after drafting them.

## 2. Follow existing conventions
The `customer` feature is a reference example, not a template to copy. Reuse a pattern only when the feature's requirements call for it; the feature's intent/spec/plan and `CLAUDE.md` take precedence.

- **Packages:** `com.rahul.booking_api.<feature>.{controller,service,repository,entity,dto}`; create only subpackages actually needed. Cross-cutting code lives in `error` and `security`.
- **Controller:** `@RestController`, `@RequestMapping("/api/<plural>")`, `@RequiredArgsConstructor`, `@ResponseStatus` for the success code, `@Valid @RequestBody`. HTTP and validation only; delegate to the service.
- **DTOs:** records with explicit Bean Validation messages. Responses use DTOs, never entities. Add custom constraints or input normalisation only if the feature needs them (customer example: trimmed input, `MaxNormalizedLength`, `MaxUtf8Bytes`, `toString` that omits secrets).
- **Service:** `@Service @RequiredArgsConstructor`, `@Transactional` on writing methods, business rules here. Where uniqueness matters, enforce it with a named database constraint and map the violation to a domain exception (customer example: check-then-`saveAndFlush`, catching `DataIntegrityViolationException`).
- **Entity:** `@Getter`, protected `@NoArgsConstructor`, private `@Builder` constructor, no `@Data`. Explicit `@Table` and `@Column(nullable, length)`, with limits checked against the value actually persisted.
- **Errors:** domain exceptions live in the feature's `service` package and are mapped in `error/ApiExceptionHandler` to `ApiError` (validation and malformed body -> 400, conflicts -> 409, etc.). Do not leak stack traces or personal data.
- **Security:** `SecurityConfig` is stateless with `anyRequest().authenticated()`; permit public endpoints explicitly by method and path. Passwords, if any, go through the `PasswordEncoder` bean. Never log secrets or personal data.
- **Style:** constructor injection only, tabs, small methods, no unused code, no mapper libraries or extra abstractions. Do not touch `pom.xml`, `application.yaml`, `docker-compose.yml`, or existing intent files unless the task requires it; ask before adding dependencies. Keep `ddl-auto: update` local-only and `open-in-view: false`; recommend Flyway for production but do not add it unprompted.

## 3. Scope
Implement only what the approved intent/spec/plan covers. No drive-by refactors, no unrelated cleanup. Note anything extra you noticed in the report instead of changing it.

## 4. Tests
Mirror the main package structure under `src/test/java`. JUnit 5, AssertJ, Mockito. Database-backed tests use PostgreSQL via Testcontainers, never H2.

Mandatory: cover every acceptance criterion in the feature's intent, including the happy path and each validation failure. Beyond that, choose tests by the feature's actual risks and add only what applies:

- **Controller/integration:** `@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)`; clean tables in `@BeforeEach`; assert status, response fields and error bodies.
- **Service:** `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`; make it non-`@Transactional` when rollback paths matter, and use `@MockitoSpyBean` to force failures.
- **Repository, DTO, custom validators:** focused tests per class when they hold logic or constraints.
- **Security:** public vs authenticated access when endpoint access rules change.
- **Edge cases, only when applicable:** boundaries and normalisation where input is limited or transformed; duplicates where uniqueness exists; concurrency where races are possible (bounded waits, `@Timeout`, executor shut down, threads below the Hikari pool size, `@RepeatedTest`); password hashing and no plain-text/hash exposure where passwords are handled; log safety and 500-response safety where personal data or secrets are handled.

State briefly in the report which risk-based tests were added or deliberately omitted, and why.

## 5. Verify
Run from the repository root (PowerShell shown; use `./mvnw` in Git Bash). Testcontainers needs Docker Desktop running; if it is not, say so rather than skipping silently.

1. Compile: `.\mvnw.cmd compile`
2. Focused: `.\mvnw.cmd test "-Dtest=<NewOrChangedTestClass>"`
3. Regression: `.\mvnw.cmd test` (full suite)

Fix failures you caused. Do not weaken or delete tests to pass. Never commit or push unless explicitly told.

## 6. Report
End with:
- **Files changed:** created/modified, one line each.
- **Test results:** commands run, pass/fail counts, any failures or skipped steps, stated honestly.
- **Risks:** concurrency, security, schema/migration, open questions.
- **Deviations from the plan:** what differed and why, or "none".
