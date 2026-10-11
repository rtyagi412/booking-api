# Spec: Customer Registration

Status: **DRAFT, awaiting final review** (Q1–Q3 answered by the user; revised after verifying behaviour against the resolved dependencies, see section 12).

Legend used throughout:
- **[REQ]** — existing requirement, taken from `features/customer-registration/intent.md` (cited as `intent §x` / `AC n`).
- **[PROPOSED]** — design decision made in this spec; open to change.
- **[DECIDED]** — resolved by the user; see section 11 (no open questions remain).

## 0. Sources and known gaps

- Requirements: `features/customer-registration/intent.md` (the only feature requirements source).
- Stack, from `pom.xml`: Spring Boot 4.1.1, Java 25, `webmvc`, Data JPA, Security, Validation, PostgreSQL, Lombok. Test: Testcontainers PostgreSQL, `webmvc-test`, `security-test`, `data-jpa-test`, `validation-test`. No Flyway, no extra password-hashing libraries (e.g. no BouncyCastle for Argon2).
- Runtime: `application.yaml` has `ddl-auto: update`, `open-in-view: false`, PostgreSQL on `${DB_HOST:localhost}:${DB_PORT:5436}`.
- Existing code: only `BookingApiApplication` and the Testcontainers test scaffolding. There is no entity, controller, security config or exception handling yet.
- **Gap:** `intent.md` cites "root `intent.md`" sections 2, 4.1, 5.1 and 6. No root `intent.md` exists, and `README.md` is a copy of the customer-registration intent with no such sections. Nothing in this spec relies on those sections. Anything they might have defined (e.g. API path conventions, error format, role model) is therefore **[PROPOSED]** here. (Q1 decided, section 11.)

## 1. Scope

**In scope [REQ]:** a new customer registers with `name`, `email`, `password`; a Customer account is created; its details (without password) are returned (intent §1, §3).

**Out of scope [REQ] (intent §2):** login, seeded accounts (Admin, Managers), password reset/change, password rules beyond non-empty, email verification, any other role-based access.

**Consequence for this spec [PROPOSED]:** because seeded Admin/Manager emails must block registration (AC 4) but seeding itself is out of scope, this feature only makes the data model *capable* of holding them (shared account table with a role). It does not create seeding code.

Traceability: requirements are `AC 1`–`AC 11` in intent §4; section 10 maps tests to them.

## 2. API

**[PROPOSED]** (the intent defines no path or status code):

| Item | Value |
|---|---|
| Endpoint | `POST /api/customers` |
| Request content type | `application/json` |
| Success status | `201 Created`, `application/json` body. No `Location` header (no GET endpoint exists in scope). |

### Request body [REQ fields, PROPOSED shape]

```json
{ "name": "Asha Rao", "email": "Asha@Example.com", "password": "s3cret" }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes [REQ] | see section 3 |
| `email` | string | yes [REQ] | see section 3 |
| `password` | string | yes [REQ] | see section 3 |

- A `role` (or any other unknown) property in the body is **ignored**: not honoured, not an error [REQ, intent §3 Role]. **[PROPOSED]** implementation note: Spring Boot's Jackson default does not fail on unknown properties; the request DTO declares no `role` field, and a test pins this behaviour (do not enable `FAIL_ON_UNKNOWN_PROPERTIES`).
- **[PROPOSED] Two distinct steps, not to be conflated:**
  1. **DTO trimming (validation only).** The request DTO is a Java record whose compact constructor trims `name` and `email` (null-safe, `String.trim()`), so Bean Validation judges the trimmed value. Without it `@Email` would reject `" a@b.com "`, yet AC 3 requires surrounding whitespace to be ignored. The DTO does **not** lower-case and does **not** decide what is stored. `password` is never trimmed.
  2. **Service normalisation (canonical storage).** `CustomerService` is the single authority for the stored form: `name` = `trim()`; `email` = `trim()` then `toLowerCase(Locale.ROOT)`. It trims again (idempotent) so that stored data never depends on the DTO having done so. Only the service lower-cases.

### Success response [REQ content, PROPOSED shape]

```json
{ "id": 1, "name": "Asha Rao", "email": "asha@example.com", "role": "CUSTOMER" }
```

- Returns id, name, email, role (intent §3 Result says "for example"). **[PROPOSED]** exactly these four fields.
- `email` is returned in its stored normalised (trimmed, lower-case) form. **[PROPOSED]**
- `name` is returned trimmed. **[PROPOSED]**
- No password and no hash, in any form [REQ, AC 1, AC 9].
- No session, cookie, token or `Set-Cookie`/`Authorization` header [REQ, AC 11].
- **[PROPOSED]** A dedicated response record is used; the entity is never serialised (CLAUDE.md §2).

## 3. Validation rules

**[REQ]** (intent §3 Input; AC 5–7, 8):

| Field | Rule |
|---|---|
| `name` | required; non-empty after trimming; at most 32 characters (after trimming). Duplicates allowed. |
| `email` | required; non-empty after trimming; at most 32 characters (after trimming); valid email format. |
| `password` | required; non-empty. No other rules [REQ]. (Technical consequence of the chosen encoder, not a new rule: at most 72 UTF-8 bytes, see below.) |

**[PROPOSED]** mapping to Bean Validation on the request record:
- `name`: `@NotBlank @Size(max = 32)`
- `email`: `@NotBlank @Size(max = 32) @Email`
- `password`: `@NotBlank` and `@MaxUtf8Bytes(72)` (the value itself is hashed untrimmed)
- **Explicit `message` on every constraint** (Bean Validation defaults differ per annotation and would make wording inconsistent); the exact strings are in section 7.
- `@MaxUtf8Bytes` is a small custom constraint (annotation + `ConstraintValidator`) that is valid when the value is `null` (so `@NotBlank` alone reports missing values) and otherwise requires `value.getBytes(UTF_8).length <= max`. `@Size` cannot be used because it counts `char`s, not bytes.
- Controller parameter annotated `@Valid @RequestBody`. All field violations are reported together (not just the first).

**[PROPOSED] interpretations of ambiguous points:**
1. "Missing", `null`, `""` and whitespace-only name/email are all refused (`@NotBlank` after trimming).
2. The 32-character limit counts characters of the trimmed value, so `" " + 32 chars + " "` is accepted and 33 chars is refused. Counted in UTF-16 `char`s as `@Size` does; non-BMP characters (e.g. emoji) count as 2. Acceptable at this scale.
3. Email format = Hibernate Validator's `@Email`. Note it accepts domain-less-TLD forms like `a@b`. Tightening that would be a new requirement, so it is not done. Confirmed by user (Q3).
4. Password "non-empty": missing, `null`, `""` and whitespace-only (`"   "`) are all refused. **[DECIDED by user]** a blank password counts as empty. A password with other characters is hashed exactly as supplied (not trimmed), so `" abc "` is valid and keeps its spaces.
5. Duplicate-name acceptance (AC 8) needs no code; it simply means no unique constraint on `name`.
6. **Effective password limit: 72 UTF-8 bytes** (not characters). The spec imposes no other maximum (no other rules, decision Q2). The 72 comes from the encoder: verified against the resolved Spring Security 7.1.1 (section 5), which throws `IllegalArgumentException("password cannot be more than 72 bytes")` for longer input instead of truncating. The limit is therefore enforced up front by validation so it yields a `400` with a `password` field error, never a `500`. Examples: 72 ASCII chars is valid, 73 is refused; 36 two-byte chars (`é`) is valid (72 bytes), 37 is refused (74 bytes) even though that is fewer than 72 characters.
7. **Known limitation (reported, not fixed):** `toLowerCase` can lengthen a few strings (e.g. `İ` U+0130 lowercases to two chars), so an email valid at 32 chars could exceed `varchar(32)` after normalisation and fail at the DB. Accepted: such addresses are practically nonexistent and the DB length constraint stays as the backstop (it would surface as a `500`). Not worth new rules under the current intent.

## 4. Persistence model and database constraints

**[PROPOSED]** (intent requires "Emails are unique across all accounts", including Admin/Manager, and a `role`, but defines no schema). Single-table decision confirmed by user (Q1).

Package layout (CLAUDE.md §2), only what is needed: `com.rahul.booking_api.customer` with `controller`, `service`, `repository`, `entity`, `dto`; plus `Role` enum and security/error configuration (see sections 6–7 for placement).

### Table `accounts`

| Column | Type | Constraints |
|---|---|---|
| `id` | `bigint` | PK, `GENERATED BY DEFAULT AS IDENTITY` (JPA `IDENTITY`) |
| `name` | `varchar(32)` | NOT NULL |
| `email` | `varchar(32)` | NOT NULL, **UNIQUE** (named constraint `uk_accounts_email`) |
| `password_hash` | `varchar(255)` | NOT NULL |
| `role` | `varchar(16)` | NOT NULL, stored as string (`@Enumerated(EnumType.STRING)`) |

- Entity name **[PROPOSED]**: `Account` (not `Customer`, because the table holds all roles), placed in `customer.entity` for now and moved to its own `account` package once a second feature needs it..
- `Role` enum **[PROPOSED]**: `CUSTOMER`, `MANAGER`, `ADMIN` (the three roles named in intent §1–§2). This feature only ever assigns `CUSTOMER`. Including all three keeps the DB check constraint Hibernate generates for the enum compatible with later seeded rows, and lets the AC 4 test insert an Admin row.
- `email` holds the **normalised** value: `trim()` then `toLowerCase(Locale.ROOT)` [REQ: stored and compared in normalised form]. Uniqueness on this single normalised column therefore gives trim- and case-insensitive uniqueness with a plain unique constraint (no functional index needed).
- Canonical normalisation happens in one place (the service; see section 2 for how it differs from DTO trimming), never in the controller or repository callers. The entity does not self-normalise via lifecycle hooks (no hidden behaviour).
- Lengths in `@Column(length = 32)` match the validation limit, so a bug that bypasses validation fails at the DB instead of silently truncating [CLAUDE.md §4].
- JPA mapping uses explicit `@Table(name, uniqueConstraints)` and `@Column(nullable = false, length = …)`. Lombok: `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, builder or constructor; **no** `@Data` on the entity [CLAUDE.md §3].
- `ddl-auto: update` will create the table locally; no Flyway (CLAUDE.md §4). Recommendation noted, not acted on: introduce Flyway with `validate` before any production-oriented schema evolution.
- Case-insensitive match does **not** rely on PostgreSQL `citext` (extension, not in scope) — lower-casing at write time is sufficient because every write path goes through the service. A future seeding feature must normalise emails the same way, or the guarantee weakens. **[PROPOSED]** an optional DB `CHECK (email = lower(btrim(email)))` could enforce that but cannot be expressed via `ddl-auto: update`; left as a recommendation for the Flyway stage.

## 5. Password handling and response data protection

**[REQ]:** secure password hash before storage; plain password never stored; neither password nor hash returned in any response (intent §3; AC 9).

**[PROPOSED]:**
- Declare a `PasswordEncoder` bean: `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (Spring Security default; produces `{bcrypt}…` hashes today and lets the algorithm be upgraded later). No new dependency. Hash stored in `password_hash`.
- **Verified behaviour (resolved `spring-security-crypto` 7.1.1 via Boot 4.1.1, Java 25; run directly against that jar):** a 72-byte ASCII password encodes (hash `{bcrypt}$2a$…`, 68 chars total, so `varchar(255)` is ample) and `matches` is true; a 73-byte password throws `IllegalArgumentException: password cannot be more than 72 bytes`; 37 × `é` (74 bytes, 37 chars) also throws; 36 × `é` (72 bytes) encodes. The limit is on UTF-8 bytes, not characters.
- Hashing happens in the service, once, immediately before building the entity. The raw password is not placed in the entity, and not kept in any field beyond the request record.
- Response DTO has no password/hash field; the entity is never returned. A test asserts the response JSON has exactly the keys `id`, `name`, `email`, `role`.
- **No logging** of the password, hash, or request body. Email is not logged on success; the duplicate path logs nothing identifying (CLAUDE.md §7). Error responses never echo the submitted password; they may name the failing field.
- The request record overrides `toString()` to omit the password (records print all components by default, which would leak it into any log or exception message that stringifies the DTO). **[PROPOSED]**
- Error responses (section 7) never contain the hash or password either.

## 6. Authentication and authorization behaviour

**[REQ]:** registration does not log the customer in and issues no session or token (AC 11). Login and role-based access are out of scope.

**Current repo state:** `spring-boot-starter-security` is on the classpath with no configuration, so Spring Boot's defaults would require HTTP Basic/form auth for every request and enable CSRF protection, which would reject an unauthenticated `POST` with 401/403.

**[PROPOSED]** add a `SecurityFilterChain` bean that:
- permits `POST /api/customers` for everyone (unauthenticated), per CLAUDE.md §7 ("explicitly permit any endpoint that must be public");
- keeps `anyRequest().authenticated()` for everything else (default-deny; no other endpoints exist yet; no new access rules are introduced);
- sets session creation policy to `STATELESS` so no `JSESSIONID` is ever issued (AC 11);
- disables CSRF. Justification: the API is stateless, uses no cookies and has no browser session to protect; with CSRF enabled the public `POST` would be rejected. Disabling is global for now because there is no other endpoint; to be revisited when login/tokens are specified.
- does **not** add an authentication mechanism: the registration endpoint issues no token and no `Authentication` is placed in the security context.

**Unauthenticated response behaviour [PROPOSED] and the entry point that delivers it:**

- **Verified:** with a custom `SecurityFilterChain` that configures no authentication mechanism (no `httpBasic`, no `formLogin`), Spring Security 7.1.1's `ExceptionHandlingConfigurer` falls back to `Http403ForbiddenEntryPoint`. An anonymous request to a protected URL would therefore return **403, not 401**. (Boot's own default chain, which adds Basic/form login, is replaced as soon as we define a chain.) An explicit entry point is thus required.
- Define `ApiAuthenticationEntryPoint implements AuthenticationEntryPoint` (package `com.rahul.booking_api.security`) and register it with `exceptionHandling(e -> e.authenticationEntryPoint(...))`. It writes status `401`, `Content-Type: application/json`, and the fixed body `{"status":401,"error":"Unauthorized","message":"authentication required"}` (same keys as the error shape in section 7, no `fieldErrors`). No `WWW-Authenticate` header is sent, because no authentication scheme exists to advertise (login is out of scope). The body is a fixed literal; no new dependency and no `ObjectMapper`.
- Applies to: any request other than `POST /api/customers` without credentials, including `GET /api/customers` and unknown paths (so unknown paths return 401, not 404, for anonymous callers). No authenticated principal exists in this feature, so no 403 (access denied) behaviour is specified.
- **Error dispatch must stay reachable:** `authorizeHttpRequests` also applies to `DispatcherType.ERROR`. Without a rule, a framework-generated error on the public endpoint (e.g. `415`, `405`) is re-dispatched to `/error`, which would be treated as unauthenticated and masked as `401`. The chain therefore permits `DispatcherType.ERROR` (`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`), verified by T18.

## 7. Error responses

**[REQ]:** duplicate email → refused with the message `email is already registered`; invalid-input refusals are distinct from it, wording left to this spec (intent §3).

**[PROPOSED]** a single JSON error shape from one `@RestControllerAdvice` (no extra libraries, no `ProblemDetail` customisation beyond what is needed):

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "invalid request",
  "fieldErrors": [ { "field": "email", "message": "must be a valid email address" } ]
}
```

`fieldErrors` is present only for validation failures.

| Situation | Status | `message` | Notes |
|---|---|---|---|
| Validation failure (AC 5, 6, 7) | `400 Bad Request` | `invalid request` | `fieldErrors` lists each field with a stable message (below). |
| Malformed JSON / wrong types / missing body | `400 Bad Request` | `malformed request body` | no `fieldErrors`; the parser's internal message is not echoed. |
| Unsupported content type / method not allowed | `415` / `405` | Spring Boot default error JSON | not handled by the advice; reachable because `ERROR` dispatch is permitted (section 6). Body shape differs from the shape above; accepted. |
| Unauthenticated, protected URL | `401 Unauthorized` | `authentication required` | from the entry point in section 6; no `fieldErrors`. |
| Duplicate email (AC 3, 4, 10) | `409 Conflict` | `email is already registered` (exactly, lower-case, no trailing period) | no `fieldErrors`; **does not** reveal whether the match was a customer/admin/manager. |
| Unexpected failure | `500` | Spring Boot default error JSON | **no catch-all `@ExceptionHandler(Exception.class)`**: it would also swallow Spring MVC's 4xx exceptions (415, 405) as 500. Boot's default error body contains no stack trace or SQL (`server.error.include-*` default to never); T22 asserts this. |

Field messages **[PROPOSED]** (stable wording; tests assert on field name, and on message where stated):
Set explicitly as `message` on each constraint; these are the only strings used:
- `name` / `email` blank or missing (`@NotBlank`): `must not be blank`
- `name` / `email` too long (`@Size(max = 32)`): `must be at most 32 characters`
- `email` bad format (`@Email`): `must be a valid email address`
- `password` blank or missing (`@NotBlank`): `must not be blank`
- `password` over 72 UTF-8 bytes (`@MaxUtf8Bytes(72)`): `must be at most 72 bytes`

When one field breaks several constraints (e.g. a 40-char malformed email fails both `@Size` and `@Email`), each message is its own `fieldErrors` entry, ordered by field then message for determinism.

Distinctness [REQ]: invalid-input is 400 with `invalid request`; duplicate is 409 with the fixed duplicate message.

**Precedence [PROPOSED]:** validation runs first. A request that is both invalid and (would be) duplicate returns 400. The duplicate check never runs on invalid input.

**Security note:** the 409 reveals that an email is registered (user enumeration). This is inherent to the REQ (the refusal message is mandated) and acceptable here; no mitigation is added.

## 8. Duplicate-email behaviour, including concurrency

**[REQ]:** unique across all accounts including seeded Admin/Manager; case-insensitive; surrounding whitespace ignored; normalised storage; message `email is already registered`; at most one account for simultaneous same-email requests (intent §3; AC 3, 4, 10).

**[PROPOSED] design — the DB constraint is the source of truth:**
1. Service method `register(request)` is `@Transactional`.
2. Normalise (service normalisation, section 2: `name` trimmed; `email` trimmed then `toLowerCase(Locale.ROOT)`), hash password, build entity with `Role.CUSTOMER` (role is set by the service, never copied from input).
3. Optional fast path: `existsByEmail(normalised)`; if true, throw `EmailAlreadyRegisteredException`. This is a UX shortcut only, **not** the safeguard (it races).
4. Persist with `saveAndFlush(...)` so the INSERT, and any unique violation, happens inside the service method rather than at commit time outside it.
5. Catch `DataIntegrityViolationException` around the flush; if the violated constraint is `uk_accounts_email`, throw `EmailAlreadyRegisteredException`; other integrity violations are rethrown (→ 500). Rationale: a `not-null`/length violation must not be reported as a duplicate.
6. `EmailAlreadyRegisteredException` is mapped by the advice to the 409 in section 7.
7. Concurrency outcome: two simultaneous inserts of the same normalised email — PostgreSQL lets exactly one commit; the other fails the unique index, is translated to the 409. No application-level locking, no serialisable isolation, no retry is needed, and no extra dependency.
8. The failed transaction is rolled back; no partial row remains.

Edge cases covered by the design: `" Asha@Example.COM "` vs `asha@example.com`; same email as a seeded Admin/Manager row (same table, same normalised column); case folding uses `Locale.ROOT` to avoid Turkish-locale surprises.

## 9. Non-functional notes

- Constructor injection only; no field `@Autowired` (CLAUDE.md §3).
- Controller does HTTP + `@Valid`; service holds normalisation, hashing, role assignment; repository is a plain `JpaRepository` with `existsByEmail` (CLAUDE.md §2).
- No mapper libraries.
- `open-in-view` remains `false`; nothing lazy is serialised.

## 10. Test scenarios mapped to acceptance criteria

All persistence/endpoint tests run against Testcontainers PostgreSQL via `TestcontainersConfiguration` (no H2) and mirror the main package layout under `src/test/java`. Controller-level tests use MockMvc against the full context (so the real security chain is exercised); unit tests may cover normalisation in isolation.

| # | Scenario | AC |
|---|---|---|
| T1 | Valid name/email/password → `201`; body has `id`, `name`, `email`, `role=CUSTOMER`; row exists in DB | 1 |
| T2 | Response JSON keys are exactly `id, name, email, role`; body string contains neither the password nor the `{bcrypt}`/`$2` hash | 1, 9 |
| T3 | Request includes `"role":"ADMIN"` → `201`, stored and returned role is `CUSTOMER` (also `"role":"BOGUS"` → still `201`, no error) | 2 |
| T4 | Register `a@example.com`, then register it again → `409`, message `email is already registered`; only one row | 3 |
| T5 | Register `asha@example.com`, then `ASHA@Example.COM` and `"  asha@example.com  "` → each `409` with the same message | 3 |
| T6 | Stored email is the normalised value (`  Asha@Example.COM ` → row has `asha@example.com`; response too) | 3 (normalised storage, REQ) |
| T7 | Insert an ADMIN and a MANAGER row directly (repository/JDBC, normalised email); registering each email → `409` | 4 |
| T8 | `name` missing / `null` / `""` / `"   "` → `400`, `fieldErrors` contains `name`; same four cases for `email` | 5 |
| T9 | `name` of 32 chars → `201`; 33 chars → `400`; same for `email` (e.g. 32-char valid address vs 33). Padded with whitespace to >32 but trimmed ≤32 → `201` | 5 |
| T10 | Invalid email formats (`plain`, `a@`, `@b.com`, `a b@c.com`) → `400`, `fieldErrors` contains `email` | 6 |
| T11 | `password` missing / `null` / `""` / `"   "` → `400`, `fieldErrors` contains `password`; `" abc "` → `201` and `matches(" abc ", hash)` is true (not trimmed) | 7 |
| T12 | Two registrations, same `name`, different emails → both `201`, two rows | 8 |
| T13 | Stored `password_hash` ≠ supplied password, is non-blank, and `PasswordEncoder.matches(raw, hash)` is true; no plain password in any column | 9 |
| T14 | Multiple invalid fields in one request → all reported in `fieldErrors` | 5–7 |
| T15 | N (e.g. 10) threads with a start latch registering the same email concurrently (also variants with differing case/whitespace) → exactly one `201`, the rest `409` with the duplicate message, exactly one row, no `500`s | 10 |
| T16 | Success response has no `Set-Cookie`/`Authorization`/token header or body field and no `JSESSIONID`; unauthenticated call succeeds; a follow-up request to a protected URL (e.g. `GET /api/customers`, `GET /anything`) with no credentials returns `401` (not `403`) with JSON body `{"status":401,"error":"Unauthorized","message":"authentication required"}` and no `WWW-Authenticate` header | 11 |
| T17 | Invalid input and duplicate produce different `message` values and statuses (400 vs 409); invalid + duplicate request → `400` | REQ distinctness |
| T18 | Malformed JSON / empty body → `400 malformed request body`; wrong content type → `415` (not masked as `401`); anonymous `PUT`/`GET` on `/api/customers` → `401` | (robustness; section 6 ERROR dispatch) |
| T19 | Non-integrity-violation DB failures are not mislabelled as duplicate (unit test of the exception translation: not-null violation → not `EmailAlreadyRegisteredException`) | 3, 10 (safeguard) |
| T20 | Request DTO `toString()` does not contain the password | 9 (data protection) |
| T21 | Password of 72 ASCII chars → `201`; 73 → `400` with `fieldErrors` `password` / `must be at most 72 bytes` (never `500`); 36 × `é` (72 bytes) → `201`; 37 × `é` (74 bytes) → `400`; a unit test that the real `PasswordEncoder` throws for 73 bytes guards the assumption on future upgrades | 7 (edge, Q2) |
| T22 | A `500` response body (default Boot error JSON) contains no stack trace or SQL | (data protection) |
| T23 | Every `fieldErrors[].message` equals one of the five strings in section 7 (bad email → `must be a valid email address`; 40-char malformed email yields both the length and format messages) | 5, 6 (consistency) |

Coverage check: AC 1 → T1, T2; AC 2 → T3; AC 3 → T4–T6; AC 4 → T7; AC 5 → T8, T9, T14; AC 6 → T10; AC 7 → T11; AC 8 → T12; AC 9 → T2, T13, T20; AC 7 edge → T21; AC 5/6 wording → T23; AC 10 → T15; AC 11 → T16.

## 11. Decisions on earlier open questions

- **Q1 — data model: DECIDED.** One shared `accounts` table with a `role` column; `Role` = `CUSTOMER`, `MANAGER`, `ADMIN`. The path `/api/customers` and the error shape stay as proposed (no contrary convention was given).
- **Q2 — password length vs BCrypt: DECIDED (user: "okay fine"), interpreted as option (c), now verified.** No new maximum is invented and no dependency is added. The effective limit is the encoder's **72 UTF-8 bytes**. Verified against the resolved Spring Security 7.1.1: longer input is **rejected** with `IllegalArgumentException` (not truncated), so validation refuses it up front with `400`, `fieldErrors` `password`, message `must be at most 72 bytes`, never `500`. Implemented by the custom `@MaxUtf8Bytes(72)` constraint (section 3); covered by T21.
- **Q3 — assumptions: DECIDED.** (i) A whitespace-only password is **refused** (changed from the draft). (ii) Plain `@Email` semantics are kept. (iii) 409 for duplicates, 400 for invalid input, with the section 7 error shape.

No open questions remain.

## 12. Revision notes (verification against repository and dependencies)

- **Resolved versions** (`mvnw dependency:tree`): Spring Boot 4.1.1, Spring Security 7.1.1, Hibernate Validator 9.1.3.Final, Java 25.
- **Corrected:** the earlier "unauthenticated → 401 by default-deny" claim was wrong for a custom chain (it yields 403); section 6 now specifies the 401 entry point and `ERROR` dispatch handling.
- **Corrected:** removed the proposed catch-all 500 handler (it would turn 415/405 into 500); section 7 updated.
- **Corrected:** email format message wording unified to `must be a valid email address` (the JSON example had `must be a well-formed email address`) and all constraint messages are set explicitly.
- **Clarified:** DTO trimming (validation only) vs service normalisation (canonical storage), section 2.
- **Verified as stated in the spec:** Boot's Jackson auto-configuration disables `FAIL_ON_UNKNOWN_PROPERTIES` (`JacksonAutoConfiguration`), so an extra `role` property is ignored.
