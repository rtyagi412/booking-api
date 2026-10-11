# Plan: Customer Registration

- Requirements: [`intent.md`](intent.md) (AC 1–11)
- Design: [`spec.md`](spec.md) (sections cited as `spec §n`; test scenarios as `T1`–`T23`)
- Status: **DRAFT, awaiting approval.** No application code exists yet.

## 1. Objective

Let a new customer register with `name`, `email`, `password` via public `POST /api/customers`. It creates a `CUSTOMER` account in a shared `accounts` table, stores a hashed password and a normalised unique email, returns `{id, name, email, role}` and does not log the customer in. Scope and out-of-scope items are exactly those in `intent.md` §2.

## 2. Repository evidence that affects the plan (reported, not silently resolved)

1. **`TestcontainersConfiguration` is package-private** (`com.rahul.booking_api`, `class TestcontainersConfiguration`). CLAUDE.md §6 requires tests to mirror the main package structure (`com.rahul.booking_api.customer...`), and a test in another package cannot `@Import` a package-private class. Task 1 makes the class `public`.
2. **Image mismatch:** the scaffold uses `postgres:latest`; `docker-compose.yml` uses `postgres:17`, so tests could run against a newer major version than local dev. Task 1 pins the scaffold's container image to `postgres:17` (test scaffolding only; `docker-compose.yml` is untouched and remains the reference). Changes to the scaffold are therefore exactly two: `public` and the image pin.
3. **No root `intent.md`, and `README.md` duplicates the feature intent** (already recorded in spec §0). Nothing in this plan depends on them.
4. **No `.git` repository** is present in the working directory, so there is nothing to commit and the plan has no commit steps (CLAUDE.md §8: never commit without instruction).
5. **Verified (spec §5, §6, §12):** Security 7.1.1 default entry point for a custom chain is 403, so Task 4 must install the 401 entry point; the delegating encoder rejects >72-byte passwords; Boot's Jackson ignores unknown properties.
6. **Dependencies/config:** none are added or changed (`pom.xml`, `application.yaml`, `docker-compose.yml` untouched). Needed libraries (Mockito, AssertJ, JsonPath, MockMvc, Testcontainers) are already on the test classpath (`spring-boot-starter-*-test`, verified with `mvnw dependency:tree`).

## 3. File inventory

Base package `com.rahul.booking_api` → `src/main/java/com/rahul/booking_api/`; tests mirror it under `src/test/java/com/rahul/booking_api/`.

**Create (main)**

| File | Purpose |
|---|---|
| `customer/entity/Role.java` | enum `CUSTOMER, MANAGER, ADMIN` |
| `customer/entity/Account.java` | JPA entity, table `accounts` (spec §4) |
| `customer/repository/AccountRepository.java` | `JpaRepository<Account, Long>` + `boolean existsByEmail(String)` |
| `customer/dto/RegisterCustomerRequest.java` | record, trimming constructor, validation, `toString` without password |
| `customer/dto/CustomerResponse.java` | record `id, name, email, role` |
| `customer/dto/MaxUtf8Bytes.java` | custom constraint annotation |
| `customer/dto/MaxUtf8BytesValidator.java` | its `ConstraintValidator<MaxUtf8Bytes, String>` |
| `customer/service/CustomerService.java` | `@Transactional register(...)`: normalise, hash, persist, translate duplicate |
| `customer/service/EmailAlreadyRegisteredException.java` | runtime exception, message `email is already registered` |
| `customer/controller/CustomerController.java` | `POST /api/customers`, `@Valid`, returns 201 |
| `security/SecurityConfig.java` | `SecurityFilterChain` + `PasswordEncoder` bean |
| `security/ApiAuthenticationEntryPoint.java` | fixed-body 401 |
| `error/ApiError.java` | record `status, error, message, fieldErrors` (`@JsonInclude(NON_NULL)`) + nested `FieldError(field, message)` |
| `error/ApiExceptionHandler.java` | `@RestControllerAdvice`: validation → 400, unreadable body → 400, duplicate → 409; **no catch-all** |

**Modify**: `src/test/java/com/rahul/booking_api/TestcontainersConfiguration.java` (add `public`; pin the PostgreSQL image to `postgres:17`, matching `docker-compose.yml`).

**Create (test)**

| File | Covers |
|---|---|
| `customer/repository/AccountRepositoryTests.java` | DB constraints; T7 seeding helper behaviour; T19 (partly); container runs PostgreSQL 17 |
| `customer/dto/RegisterCustomerRequestTests.java` | trimming, constraint messages, `toString` (T8–T11, T14, T20, T21, T23; no Spring context, plain `Validator`) |
| `customer/dto/MaxUtf8BytesValidatorTests.java` | byte-vs-char boundaries (T21) |
| `security/SecurityConfigTests.java` | 401 entry point, no `WWW-Authenticate`, encoder 73-byte guard (T16 part, T21 guard) |
| `customer/service/CustomerServiceTests.java` | normalisation, role, hashing, duplicate paths incl. forced constraint path (T3 service side, T4–T7, T13, T19) |
| `customer/controller/CustomerControllerTests.java` | MockMvc HTTP behaviour (T1–T14, T16–T18, T21, T23; **not** T22) |
| `customer/controller/CustomerRegistrationServerErrorTests.java` | T22 against a real server (`RANDOM_PORT`, JDK `java.net.http.HttpClient`) |
| `customer/controller/CustomerRegistrationConcurrencyTests.java` | T15 |

Conventions for every MockMvc integration test class (so Spring caches **one** context and **one** PostgreSQL container for the whole run): annotate with `@SpringBootTest`, `@AutoConfigureMockMvc` (`org.springframework.boot.webmvc.test.autoconfigure`, verified in `spring-boot-webmvc-test` 4.1.1) and `@Import(TestcontainersConfiguration.class)`, identical on all, and clean with `accountRepository.deleteAll()` in `@BeforeEach`. `CustomerServiceTests` additionally needs `@MockitoSpyBean` on `AccountRepository`; if that creates a second context, accept the extra container (correctness over speed). `CustomerRegistrationServerErrorTests` is the only class with `@SpringBootTest(webEnvironment = RANDOM_PORT)` (no `@AutoConfigureMockMvc`) and a `@MockitoBean CustomerService`, so it gets its own context and container; this is accepted. It uses the JDK `HttpClient` because it needs no new dependency (CLAUDE.md §8); the port is injected with `@LocalServerPort`. Tests assert via AssertJ/JsonPath. No H2.

## 4. Task list

Each task is independently reviewable and ends with its verification. "Run" commands are in section 6.

### Task 0: Baseline
- [x] Confirm Docker Desktop is running (`docker info`).
- [x] `.\mvnw.cmd compile` and `.\mvnw.cmd test "-Dtest=BookingApiApplicationTests"` pass before any change.
- Depends on: nothing. Verify: both succeed; if `contextLoads` fails, stop and report (environment issue, not feature).

### Task 1: Make the Testcontainers scaffold usable from subpackages
- [x] Modify `TestcontainersConfiguration.java`: `class` → `public class` (bean method stays package-private).
- [x] In the same file, replace the `postgres:latest` image with `postgres:17`, matching `docker-compose.yml`. Change nothing else (no other containers, no reuse flags).
- Depends on: 0. Verify: `BookingApiApplicationTests` still passes; the container starts from the `postgres:17` image (first run may pull it). The check that the server really reports major version 17 is in Task 2.

### Task 2: Persistence model
- [x] Create `Role`, `Account` (`@Entity`, `@Table(name = "accounts", uniqueConstraints = @UniqueConstraint(name = "uk_accounts_email", columnNames = "email"))`; `@Column(nullable = false, length = 32)` for `name` and `email`, `@Column(name = "password_hash", nullable = false, length = 255)`, `@Enumerated(STRING) @Column(nullable = false, length = 16)` for `role`; `@Id @GeneratedValue(IDENTITY)`; `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, an all-args-but-id constructor or `@Builder`; **no `@Data`**).
- [x] Create `AccountRepository` with `existsByEmail`.
- [x] Write `AccountRepositoryTests`: saving two rows with the same email → `DataIntegrityViolationException` naming `uk_accounts_email`; null name/email/hash/role → violation; 33-char name/email → violation; ADMIN and MANAGER rows persist; same name different emails persists (T12 DB side); `existsByEmail` true/false; `SELECT version()` (native query) starts with `PostgreSQL 17`, proving the pin.
- Depends on: 1. Verify: run `AccountRepositoryTests`; inspect the Hibernate DDL log (`format_sql` is on) or `\d accounts` in the container to confirm unique constraint name, `varchar(32)`, `not null`, and role column. Record whether Hibernate generated a CHECK for the enum (expected, and why all three roles exist).

### Task 3: Request/response DTOs and byte-length constraint
- [x] Create `MaxUtf8Bytes` + `MaxUtf8BytesValidator` (null valid; `getBytes(UTF_8).length <= max`).
- [x] Create `RegisterCustomerRequest` record: components `name`, `email`, `password`; compact constructor trims `name`/`email` when non-null (never `password`; **no lower-casing here**, spec §2); constraints with explicit messages (spec §7): `name` `@NotBlank("must not be blank") @Size(max=32, "must be at most 32 characters")`; `email` same plus `@Email("must be a valid email address")`; `password` `@NotBlank("must not be blank") @MaxUtf8Bytes(value=72, message="must be at most 72 bytes")`; override `toString()` to omit password. Unknown `role` property needs no field.
- [x] Create `CustomerResponse` record (`Long id, String name, String email, Role role`), with a static factory from `Account`.
- [x] Tests (plain `Validation.buildDefaultValidatorFactory()`, no Spring): `RegisterCustomerRequestTests` for T8, T9 (boundary 32/33 after trim), T10 (formats `plain`, `a@`, `@b.com`, `a b@c.com`), T11, T14, T20, T23 (exact messages; 40-char malformed email yields both messages); `MaxUtf8BytesValidatorTests` for 72/73 ASCII, 36/37 × `é`, null.
- Depends on: 2 (for `Role`). Verify: run the two test classes.

### Task 4: Security configuration and 401 entry point
- [x] Create `ApiAuthenticationEntryPoint`: status 401, `Content-Type: application/json`, body exactly `{"status":401,"error":"Unauthorized","message":"authentication required"}`, no `WWW-Authenticate`.
- [x] Create `SecurityConfig` (`@Configuration`): `SecurityFilterChain` with `csrf` disabled, `sessionManagement` `STATELESS`, `exceptionHandling` using the entry point, `authorizeHttpRequests`: `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`, `requestMatchers(HttpMethod.POST, "/api/customers").permitAll()`, `anyRequest().authenticated()`; no `httpBasic`/`formLogin`. Plus `@Bean PasswordEncoder` = `PasswordEncoderFactories.createDelegatingPasswordEncoder()`.
- [x] Write `SecurityConfigTests` (MockMvc): anonymous `GET /anything` and `GET /api/customers` → 401 with the exact JSON body and no `WWW-Authenticate`; no `Set-Cookie`; plus a plain unit assertion that the `PasswordEncoder` bean encodes 72 bytes and throws `IllegalArgumentException` for 73 bytes (guard for T21), and that hashes start with `{bcrypt}`.
- Depends on: 1. Verify: run `SecurityConfigTests`. (The public POST permit is verified in Task 7, when the controller exists.)

### Task 5: Error model
- [x] Create `error/ApiError` and `error/ApiExceptionHandler` and `customer/service/EmailAlreadyRegisteredException`.
  - `MethodArgumentNotValidException` → 400, `message: "invalid request"`, `fieldErrors` sorted by field then message.
  - `HttpMessageNotReadableException` → 400, `message: "malformed request body"`, no parser detail.
  - `EmailAlreadyRegisteredException` → 409, `message: "email is already registered"` (exception message is this exact constant), no `fieldErrors`.
  - No `Exception.class` handler (spec §7).
- Depends on: 3 (for nothing but naming; may be built in parallel with 3 and 4). Verify: compiles (`.\mvnw.cmd compile`); behaviour is asserted in Task 7.

### Task 6: Service
- [x] Create `CustomerService` (`@Service`, `@RequiredArgsConstructor`, constructor injection of `AccountRepository`, `PasswordEncoder`): `@Transactional CustomerResponse register(RegisterCustomerRequest)`:
  1. `name = request.name().trim()`; `email = request.email().trim().toLowerCase(Locale.ROOT)`;
  2. if `existsByEmail(email)` throw `EmailAlreadyRegisteredException`;
  3. build `Account` with `Role.CUSTOMER` and `passwordEncoder.encode(request.password())`;
  4. `saveAndFlush`; catch `DataIntegrityViolationException`; if the cause chain contains a Hibernate `ConstraintViolationException` whose `getConstraintName()` equals `uk_accounts_email` (case-insensitive), throw `EmailAlreadyRegisteredException`; otherwise rethrow;
  5. return `CustomerResponse` (role never taken from the request).
  - Verify during the task that PostgreSQL + Hibernate 7 actually report the constraint name in `getConstraintName()`; if not, fall back to SQLState `23505` plus constraint name from `PSQLException.getServerErrorMessage().getConstraint()` and update spec §8 step 5 accordingly. Report either way.
- [x] `CustomerServiceTests`: `"  Asha@Example.COM "` stored as `asha@example.com`, role `CUSTOMER` (T6, T3 service side); stored hash ≠ raw, non-blank, `matches` true, `" abc "` keeps spaces (T13, T11); duplicate by case/whitespace and by seeded ADMIN/MANAGER row → exception (T4, T5, T7); **forced constraint path:** `@MockitoSpyBean AccountRepository` with `existsByEmail` stubbed to `false` while the row exists → still `EmailAlreadyRegisteredException`, one row (T10 deterministic part); a row violating NOT NULL / length (e.g. forced 33-char name bypassing validation) is **not** reported as duplicate (T19); two same-name accounts allowed (T12).
- Depends on: 2, 3, 4 (encoder bean), 5 (exception). Verify: run `CustomerServiceTests`.

### Task 7: Controller and end-to-end behaviour
- [x] Create `CustomerController` (`@RestController`, `@RequestMapping("/api/customers")`, `@PostMapping`, `@ResponseStatus(CREATED)` or `ResponseEntity`, `@Valid @RequestBody`, constructor injection of `CustomerService`, returns `CustomerResponse`; no `Location`).
- [x] `CustomerControllerTests` (MockMvc, JsonPath), covering: T1, T2 (exact key set; body contains neither raw password nor `{bcrypt}`/`$2`), T3 (`role: ADMIN` and `role: BOGUS` → 201, `CUSTOMER`), T4–T7 via HTTP, T8–T11, T14 (statuses and `fieldErrors` content), T12, T16 (anonymous POST succeeds; no `Set-Cookie`, no `Authorization`, no `JSESSIONID`; follow-up protected GET → 401 exact body), T17 (400 vs 409, different messages; invalid+duplicate → 400), T18 (malformed JSON, empty body → 400 `malformed request body`; `text/plain` → 415 not 401; anonymous PUT/GET `/api/customers` → 401), T21 over HTTP (72 vs 73 bytes, 36 vs 37 × `é`), T23. T22 is deliberately **not** here; it moves to the real-server test below.
- [x] Create `CustomerRegistrationServerErrorTests` (T22): `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Import(TestcontainersConfiguration.class)`, `@MockitoBean CustomerService` whose `register` throws `new RuntimeException("SENSITIVE-MARKER jdbc:postgresql://host/db select * from accounts")`. Send a valid `POST /api/customers` over a real socket with `java.net.http.HttpClient` (bounded connect and request timeouts). Assert the actual response: status 500; the body (read as a string) contains none of the marker text, `jdbc`, `select`, `Exception`, `java.`, `com.rahul`, `org.springframework`, `trace`; if JSON, it has no `trace`/`exception` keys; the response is not an HTML stack-trace page; no `Set-Cookie`. Also assert a second request still works (a normal registration returns `201` once the mock is reset or for a path not mocked), showing the server stays healthy. This proves the real servlet container, ERROR dispatch, `permitAll` for `DispatcherType.ERROR` and Boot's default `server.error.*` settings together, which MockMvc does not.
- Depends on: 3, 4, 5, 6. Verify: run `CustomerControllerTests` and `CustomerRegistrationServerErrorTests`; this task is the first point where Tasks 4 and 5 are proven end to end. If the real-server 500 body leaks anything, report it and propose a fix; do not weaken the assertions or add a catch-all silently (spec §7).

### Task 8: Concurrency against PostgreSQL
- [x] `CustomerRegistrationConcurrencyTests`: 8 threads (below the default Hikari pool of 10) released together via `CountDownLatch`, all POSTing the same email in varying case/whitespace (T15); assert exactly one `201`, all others `409` with `email is already registered`, **no** `500`, and `accountRepository.count() == 1`. Use `@RepeatedTest(3)` to expose races. A second test posts 8 different emails concurrently → 8 × `201` (no false conflicts).
- [x] Bounded and clean: every test has `@Timeout(30)` (seconds); the executor is created per test and shut down in a `finally` (`shutdownNow()` then `awaitTermination` for a few seconds, failing the test if threads do not stop); the main thread waits on the ready latch and workers wait on the start latch, both with a timeout (e.g. 10 s) whose expiry fails the test; results are collected with `Future.get(timeout)` (e.g. 20 s); a worker exception surfaces as a failure, never swallowed; no unbounded `await()`/`get()`/`join()` anywhere.
- Depends on: 7. Verify: run the class once (it already repeats 3 times). Do not rerun it just to gain confidence; rerun only after a failure, or after a change to registration, constraint translation, transaction setup or the test itself. Any flake is a defect to report, not to retry away.

### Task 9: Whole-feature verification and traceability
- [x] `.\mvnw.cmd test` (full suite, Docker running): all green, no skipped tests. This is the single final run; no extra repeated runs of the concurrency class.
- [x] Walk the AC table in section 5 and tick each against passing tests.
- [ ] Optional manual smoke (not a substitute for tests): `docker compose up -d`, `.\mvnw.cmd spring-boot:run`, then `curl.exe` the scenarios in section 6; `docker compose down` afterwards (keeps data).
- [x] Report honestly: failures, skipped steps, and the deviations in section 2 / spec §12.
- Depends on: 1–8.

## 5. Test coverage map

| AC | Scenarios | Test classes |
|---|---|---|
| 1 Valid registration, no password in response | T1, T2 | `CustomerControllerTests` |
| 2 Role fixed to CUSTOMER | T3 | `CustomerControllerTests`, `CustomerServiceTests` |
| 3 Duplicate refused (case/whitespace) | T4, T5, T6 | `CustomerControllerTests`, `CustomerServiceTests` |
| 4 Seeded Admin/Manager email refused | T7 | `CustomerServiceTests`, `CustomerControllerTests` (rows inserted via repository) |
| 5 Name/email missing, blank, >32 | T8, T9, T14, T23 | `RegisterCustomerRequestTests`, `CustomerControllerTests` |
| 6 Invalid email format | T10, T23 | same |
| 7 Empty password | T11, T21 (72-byte limit) | `RegisterCustomerRequestTests`, `MaxUtf8BytesValidatorTests`, `SecurityConfigTests`, `CustomerControllerTests` |
| 8 Same name allowed | T12 | `AccountRepositoryTests`, `CustomerServiceTests`, `CustomerControllerTests` |
| 9 Hash stored, nothing returned | T2, T13, T20, T22 | `CustomerServiceTests`, `RegisterCustomerRequestTests`, `CustomerControllerTests` (T2), `CustomerRegistrationServerErrorTests` (T22, real server) |
| 10 Concurrent same-email registration | T15 (+ forced constraint path T4-variant, T19) | `CustomerRegistrationConcurrencyTests`, `CustomerServiceTests`, `AccountRepositoryTests` |
| 11 No login | T16 | `SecurityConfigTests`, `CustomerControllerTests` |
| Public endpoint, 401 for others, error consistency | T16, T17, T18, T23 | `SecurityConfigTests`, `CustomerControllerTests` |
| Unexpected-error response leaks nothing | T22 | `CustomerRegistrationServerErrorTests` |

## 6. Commands (Windows / PowerShell, repository root)

| Purpose | Command |
|---|---|
| Docker check | `docker info` |
| Compile | `.\mvnw.cmd compile` |
| One test class | `.\mvnw.cmd test "-Dtest=CustomerServiceTests"` |
| Real-server error test | `.\mvnw.cmd test "-Dtest=CustomerRegistrationServerErrorTests"` |
| Several classes | `.\mvnw.cmd test "-Dtest=RegisterCustomerRequestTests,MaxUtf8BytesValidatorTests"` |
| Full suite (final) | `.\mvnw.cmd test` |
| Package | `.\mvnw.cmd package` |
| DB for manual run | `docker compose up -d` / `docker compose ps` / `docker compose down` |
| Run app | `.\mvnw.cmd spring-boot:run` |

Manual smoke examples (PowerShell, app running on 8080):

```powershell
curl.exe -i -X POST http://localhost:8080/api/customers -H "Content-Type: application/json" -d '{\"name\":\"Asha\",\"email\":\" Asha@Example.com \",\"password\":\"s3cret\",\"role\":\"ADMIN\"}'
curl.exe -i -X POST http://localhost:8080/api/customers -H "Content-Type: application/json" -d '{\"name\":\"Asha\",\"email\":\"ASHA@example.com\",\"password\":\"x\"}'
curl.exe -i http://localhost:8080/api/customers
```

Expected: `201` with role `CUSTOMER` and lower-cased email, no `Set-Cookie`; `409` `email is already registered`; `401` with the JSON body.

## 7. Definition of done

- All tasks ticked; `.\mvnw.cmd test` passes with no skipped tests; each AC row in section 5 is covered by a passing test.
- No change to `pom.xml`, `application.yaml`, `docker-compose.yml`, `intent.md`; the only change to existing files is `TestcontainersConfiguration` (`public` and the `postgres:17` pin), and a test confirms the container runs PostgreSQL 17.
- T22 passes against a real server (`RANDOM_PORT`, HTTP client); the concurrency tests have bounded timeouts, guaranteed executor shutdown and three repetitions, with no redundant reruns.
- No plain password or hash in any log, response or `toString`.
- Nothing committed or pushed.
