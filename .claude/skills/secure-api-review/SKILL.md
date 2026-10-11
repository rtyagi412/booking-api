---
name: secure-api-review
description: Read-only security review of REST endpoints in the booking-api Spring Boot project. Use for security assessments of an endpoint, controller or feature, reviews of security-related changes (SecurityConfig, authentication, authorization, validation, error handling, entities/constraints, logging), or when asked "is this endpoint secure?". Reports confirmed vulnerabilities, potential risks and missing security tests separately. Does not implement features (use spring-boot-api) and does not modify code unless explicitly told to.
---

# secure-api-review

Read-only security assessment for this repository. `CLAUDE.md` and `pom.xml` are the source of truth. Implementation conventions belong to `spring-boot-api`; do not restate or re-run that workflow here. This skill only judges code against the security conventions below.

## 0. Ground rules
- **Read-only by default.** Do not edit application code, tests, config, docs or `.claude` settings. Write fixes only if the user explicitly asks, and then follow `spring-boot-api` and `CLAUDE.md` §5 (security changes need an approved `spec.md` and `plan.md` first).
- Never commit or push. Stay inside this repository (`CLAUDE.md` §8).
- **No invented findings.** Every finding must cite code you read. If you cannot show evidence, it is not a finding. Report it as a question or omit it.
- **No unverified test claims.** Say a test "passed" only if you ran it in this session and saw the result. Otherwise write "exists, not run". Running tests needs Docker Desktop and is optional; ask before running if the user only wanted a read-through. If Docker is down, say so.
- If requirements are ambiguous or cite missing sections (README sections 2, 4.1, 5.1, 6 are known to be absent), ask the user. Do not guess intended behaviour (for example whether a 409 on duplicate email is an accepted disclosure).

## 1. Scope and inputs
1. Determine the target: a named endpoint/feature, the pending diff (`git diff`, `git status`), or the whole API. If unclear, ask.
2. Read, in order: `CLAUDE.md`; the feature's `intent.md`, `spec.md`, `plan.md`; the feature's `REVIEW.md` if present (`features/<feature>/REVIEW.md`); `pom.xml` (actual stack; do not assume libraries or APIs not present); `application.yaml`.
3. Read the target code and its tests in full, plus the cross-cutting code in `security/` and `error/`.
4. Treat prior review dispositions as context, not proof: re-verify against current code. Respect items the user marked deferred or accepted, but list them as "previously decided" rather than re-raising them as new.

## 2. Repository security conventions (baseline)
Derived from the current code. If the code has moved on, trust the code and note the drift.

**Authentication / session** (`security/SecurityConfig`, `ApiAuthenticationEntryPoint`)
- Stateless (`SessionCreationPolicy.STATELESS`), CSRF disabled, `anyRequest().authenticated()`; public endpoints are permitted explicitly by HTTP method and path (currently only `POST /api/customers`). `DispatcherType.ERROR` is permitted so error dispatches keep their real status.
- Unauthenticated requests get a fixed JSON 401 with no `WWW-Authenticate` header, no cookie and no session.
- There is currently no login, token or credential-based authentication mechanism. Authenticated access to anything cannot yet succeed. Do not report that as a defect by itself; do report any new endpoint that assumes it exists.
- CSRF disabled is only acceptable while there are no cookie/session credentials. Flag it if a change introduces cookies, sessions or browser-held credentials.

**Authorization**
- `Role` exists on `Account` (`CUSTOMER` is assigned server-side at registration; the request DTO has no role field). No method security, ownership checks or role rules exist yet. For any new endpoint check: which role may call it, whether object-level access (IDOR/BOLA) is enforced, whether the role/ownership comes from the authenticated principal and never from the request, and that mass assignment of `role`, `id` or similar is impossible.
- Check that a public matcher is as narrow as intended (method + exact path; watch for broad patterns, trailing-slash and path-variant behaviour of the Spring version in `pom.xml`).

**Passwords and secrets**
- Hash with the `PasswordEncoder` bean (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`, `{bcrypt}` prefix). Never store, log, return or `toString` plain passwords or hashes. Records carrying secrets override `toString` to omit them (`RegisterCustomerRequest`).
- BCrypt input limit is 72 UTF-8 bytes; the repo enforces it at validation (`@MaxUtf8Bytes`). A longer value reaching the encoder throws `IllegalArgumentException` (a 500 if unvalidated).
- No hard-coded real secrets. `application.yaml`/`docker-compose.yml` credentials are local-dev defaults overridable via `DB_*` variables; flag anything else.

**Request validation**
- Bean Validation on request DTOs (records) with explicit messages; controllers use `@Valid @RequestBody`. Custom constraints: `@MaxNormalizedLength` (limit judged on the trimmed, lower-cased value that is persisted) and `@MaxUtf8Bytes`.
- Check every client-controlled field: nullability, blank, length (against the persisted, normalised form, per `CLAUDE.md` §4), format, enum/role fields, path and query parameters (not covered by `@Valid` on a body; look for `@Validated`/constraints), and content type.
- Look for unvalidated input reaching queries (Spring Data derived/`@Query` with concatenation, native SQL), logs, or error messages.

**Sensitive-data exposure**
- Responses use DTOs, never entities (`CustomerResponse`: id, name, email, role; no hash). Check new DTOs and any `from(entity)` mapping for added fields.
- Logging: no emails, passwords or hashes beyond need. `application.yaml` sets `org.hibernate.orm.jdbc.error: ERROR` so PostgreSQL constraint "Detail" text (which quotes row values) is not logged. Check that new SQL logging (`show_sql`, `format_sql` output, debug levels) and exception logging do not reintroduce this.
- Error bodies must not carry stack traces, SQL, constraint names, class names or submitted values.

**Exception handling** (`error/ApiExceptionHandler`, `ApiError`)
- Mapped: validation and malformed body -> 400 `ApiError` (field errors sorted), duplicate email -> 409, anything unmapped -> 500 via the framework error path without sensitive details. Unsupported media type goes through the error dispatch and must not become a misleading 401.
- Check new domain exceptions are mapped deliberately, status codes are correct (400 vs 401 vs 403 vs 404 vs 409), messages are generic, and failures do not leak whether an account exists beyond what the intent explicitly accepts. Flag user-enumeration surfaces (registration 409, future login/reset) as a **potential risk** unless the intent decides it.
- Absence of a handler for a security exception (for example `AccessDeniedException` -> 403 JSON) matters once authorization exists; note it then, not before.

**Database integrity** (`Account`, `CustomerService`, `AccountRepository`)
- Integrity enforced in the database: named unique constraint (`uk_accounts_email`), `nullable = false`, `length` on every column. Services are `@Transactional` on writes; uniqueness uses check-then-`saveAndFlush` and maps `DataIntegrityViolationException` by constraint name to a domain exception, so concurrent duplicates give 409 and not 500.
- Check: stored form equals validated form (trim/lower-case lengthening, Unicode edge cases); every unique/not-null/length rule has a validation counterpart so violations cannot become 500s or truncation; unrelated integrity failures do not leak details; no `@Data` on entities; `open-in-view: false` kept; `ddl-auto: update` is local-only (recommend Flyway + `validate` for production, do not add it).
- Look for TOCTOU races, lost updates and missing locking on any new read-modify-write flow.

**Other**
- Dependency and configuration risks visible in `pom.xml`/`application.yaml` (unexpected actuator or devtools exposure, permissive CORS, debug flags, `server.error.*` including stack/message/binding errors, default credentials outside local dev). Report only what is present. Do not run dependency scanners or fetch advisories unless asked.
- No rate limiting, lockout or brute-force protection exists. State it as a potential risk or an accepted gap only where an endpoint (registration, future login) makes it relevant. Check the intent for "out of scope" first.

## 3. Test coverage check
Existing security-relevant tests to compare against (mirror under `src/test/java`; Testcontainers PostgreSQL, never H2):
- `security/SecurityConfigTests`: anonymous 401 exact body/no challenge/no session, encoder limits and `{bcrypt}` prefix.
- `customer/controller`: `CustomerControllerTests` (API contract), `CustomerRegistrationLogSafetyTests` (no password/hash/email in logs), `CustomerRegistrationServerErrorTests` (500 without sensitive details, 415 not 401, real-socket 401), `CustomerRegistrationConcurrencyTests` (duplicate race), `CustomerRegistrationNormalizedEmailLengthTests` (normalised-length boundary).
- DTO/validator/service/repository tests for constraints and hashing.

For the target, list which of these risks have a test and which do not: public vs authenticated vs forbidden access per endpoint and method; role/ownership denial; mass-assignment attempts; each validation boundary (including normalised length and byte length); malformed/oversized/wrong-content-type bodies; no secrets in responses, logs and `toString`; 4xx/5xx bodies free of internals; duplicate/concurrency handling; unrelated integrity violations. A missing test is a **coverage gap**, not a vulnerability. Say whether the gap hides a confirmed issue, a potential one, or neither.

## 4. Classification (keep these separate in the report)
- **Confirmed vulnerability:** you can point to code and show the failing behaviour, by reading it unambiguously or by reproducing it (a test you ran, a request you made against a locally running app the user authorised). State how it was confirmed.
- **Potential risk:** plausible from the code or design but depends on something unverified (an assumption, future change, deployment setting, unmeasured behaviour). State exactly what is unverified and what would confirm it.
- **Coverage gap:** behaviour that may be fine but no test would catch a regression.
- **Previously decided:** deferred or accepted items recorded in `REVIEW.md`/`spec.md`; restate in one line, do not re-litigate.
- **Positive observations:** controls verified working, kept brief so the reader knows what was checked.

## 5. Severity
Use the definitions in `REVIEW.md` if the reviewed feature's `REVIEW.md` (or a root `REVIEW.md`) defines severity levels. At the time of writing, `features/customer-registration/REVIEW.md` does **not** define a severity scale (it records dispositions only and classifies nothing as a defect). In that case use the fallback below and say in the report that it is a fallback, not a repository standard:
- **Critical:** unauthenticated or low-privilege compromise of accounts/data, auth bypass, secret or credential exposure.
- **High:** exploitable leak of sensitive data, broken authorization on a reachable endpoint, injection.
- **Medium:** reachable weakness with limited impact or preconditions (information disclosure, unhandled integrity failure causing 500, user enumeration).
- **Low:** hardening or defence-in-depth gaps with no demonstrated exploit path.
- **Info:** observations and coverage gaps.

Severity reflects demonstrated reachability, not hypothetical worst cases. Do not rate a potential risk above what the evidence supports.

## 6. Procedure
1. Inventory the entry points: every `@RequestMapping` and its `SecurityConfig` matcher result (public, authenticated, role-restricted).
2. Walk each entry point: auth -> authorization -> input validation -> service/DB -> response mapping -> error paths -> logging.
3. Trace each persisted field from submitted value to column (normalisation, length, constraint).
4. Compare against section 3 for coverage.
5. Optionally run only the relevant test classes with `.\mvnw.cmd test "-Dtest=<Class>"` (never skip silently if Docker is down). Report exact commands and counts, or state that nothing was run.
6. Write the report. Do not fix anything.

## 7. Report format
Start with scope (what was read, what commit/diff state, what was and was not run). Then one section per class from section 4, in this order: Confirmed vulnerabilities, Potential risks, Coverage gaps, Previously decided, Positive observations.

Each finding uses:
- **ID and title**
- **Severity** (state which scale)
- **Location:** `path/File.java:line`
- **Evidence:** the quoted line(s) or observed behaviour, and how it was confirmed (read / reproduced / ran test X)
- **Impact:** who can do what, under which preconditions
- **Recommendation:** the smallest fix consistent with repo conventions, plus the test that should cover it. Describe it; do not apply it.

Close with: tests run (commands and results, or "none run"), what was out of scope or unverifiable, open questions for the user, and "No files were modified." If there are no findings in a class, write "none found" and say what was checked. An empty result is acceptable; padding is not.
