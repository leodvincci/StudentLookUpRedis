# Devlog: Student Lookup API with Redis Cache-Aside — Feature Build & Controller Refactor

**Date:** 2026-03-03
**Time:** 17:30 CST (America/Chicago)
**Branch:** `feature/student-lookup-with-redis-cache`
**Range:** `2a6ae29..HEAD` (local `main`..HEAD — all feature commits; `origin/main` was merged after the push)
**Commits:**
- `e79529a` — Initialize Spring Boot project scaffold
- `39462f2` — Add Student entity and JPA repository
- `58debbb` — Add Redis cache-aside adapter
- `bb1bfa8` — Add service layer and REST controller
- `c76a3ae` — Configure H2, JPA, and Redis properties
- `be002cf` — Add project README with setup and usage instructions
- `956cdc7` — Update README with example responses, logs, and code snippets
- `91f26c2` — **Refactor StudentLookupController and StudentLookupService for improved REST API structure and student creation logic** ← _primary focus_

---

## Overview

### What problem was I solving?
Building a complete, working Spring Boot REST API that demonstrates the **cache-aside (lazy-load)** and **write-through** caching patterns using Redis in front of an H2 in-memory database. The feature started from a bare `Initial empty commit` and delivered a functional student record management service. The final commit corrected two bugs introduced in the initial controller/service wiring: a wrong Spring stereotype annotation and a duplicated constructor parameter.

### Before state
An empty Git repository with only an initial placeholder commit on `main`. No application code, no configuration, no tests.

### Most important outcomes
- A runnable Spring Boot 4 / Java 21 REST API with three endpoints: `POST /student`, `GET /students`, `GET /student/{id}`
- Cache-aside read path: Redis → H2 fallback → populate cache
- Write-through create path: save to H2 + write to Redis atomically in the adapter
- Configurable TTL via `app.cache.expiration` (default 120 s)
- `@Controller` → `@RestController` bug fix: the app would have failed to serve JSON without this change
- Server-side UUID generation enforced: clients can no longer inject arbitrary IDs via the create endpoint
- Full README documenting architecture, endpoints, configuration, and example log output

---

## Commit-by-Commit Breakdown

---

### `e79529a` — Initialize Spring Boot project scaffold

**Intent:** Bootstrap the Maven project structure from Spring Initializr with the full dependency set needed for the feature.

**Files touched:**
| File | Reason |
|---|---|
| `pom.xml` | Declares Spring Boot 4.0.3, Spring Web, Spring Data JPA, Spring Data Redis, H2 (runtime), and test starters |
| `StudentLookUpRedisApplication.java` | Main entry point with `@SpringBootApplication` |
| `StudentLookUpRedisApplicationTests.java` | Default `contextLoads()` smoke test |
| `.gitignore`, `.gitattributes` | Standard IntelliJ + Maven ignores; LF/CRLF normalization for `mvnw` |
| `mvnw`, `mvnw.cmd` | Maven wrapper scripts |

**Key code changes:** None beyond boilerplate. `@SpringBootApplication` enables component scanning, JPA auto-config, and Redis auto-config.

**Architecture notes:** Sets up the runtime classpath. Notably, `spring-boot-starter-data-redis` pulls in Lettuce as the default Redis client — no Jedis configuration needed.

**Risk / edge cases:** Spring Boot 4 uses Jakarta EE 10 namespace (`jakarta.*`). Any `javax.*` imports will fail at compile time.

**Verification:** `mvn test` — `contextLoads()` should pass once Redis is reachable (or the test must mock it).

---

### `39462f2` — Add Student entity and JPA repository

**Intent:** Define the core domain model and its persistence port.

**Files touched:**
| File | Reason |
|---|---|
| `Student.java` | Domain model + JPA `@Entity` |
| `StudentLookupRepo.java` | Spring Data JPA repository interface |

**Key code changes:**

```java
// Student.java — two constructors
public Student(String firstName, String lastName, String collegeMajor) {
    this.id = String.valueOf(UUID.randomUUID()); // server-generated ID
    ...
}
public Student(String id, String firstName, String lastName, String collegeMajor) {
    this.id = id; // explicit ID override (used before refactor; still exists)
    ...
}
```

`StudentLookupRepo` extends `JpaRepository<Student, String>` — gives `findById`, `findAll`, `save`, `deleteById` for free.

**Architecture notes:**
- `Student` is both the domain object and the JPA entity (no DTO/mapper separation). This is fine for a demo but violates clean architecture — the entity carries both domain and persistence concerns.
- No `@Table(name = ...)` annotation means Hibernate maps to a table named `STUDENT` by default.
- `@Id` with no `@GeneratedValue` — ID generation is fully application-controlled (UUID in constructor). Hibernate will not auto-generate IDs.

**Risk / edge cases:**
- The no-arg constructor (`public Student() {}`) is required by Hibernate for entity reconstruction. If it were removed, JPA would throw at startup.
- The `id`-taking constructor is still present after the refactor. It's unused by the service layer but remains a potential footgun if someone calls it directly.

**Verification:** `mvn test -Dtest=StudentLookUpRedisApplicationTests`

---

### `58debbb` — Add Redis cache-aside adapter

**Intent:** Implement the caching logic as an isolated infrastructure component.

**Files touched:**
| File | Reason |
|---|---|
| `StudentLookupRedisAdapter.java` | Cache-aside read + write-through create |

**Key code changes:**

```java
// findById — cache-aside
public Optional<Student> findById(String id) {
    String key = KEY_PREFIX + id;          // "student:<uuid>"
    String json = redis.opsForValue().get(key);
    if (json != null) { return Optional.of(mapper.readValue(json, Student.class)); }
    Optional<Student> fromDb = studentLookupRepo.findById(id);
    fromDb.ifPresent(s -> redis.opsForValue().set(key, mapper.writeValueAsString(s), ttl));
    return fromDb;
}

// createNewStudent — write-through
public void createNewStudent(Student student) {
    studentLookupRepo.save(student);
    redis.opsForValue().set(KEY_PREFIX + student.getId(), mapper.writeValueAsString(student), ttl);
}
```

**Architecture notes:**
- `StringRedisTemplate` is used (plain string key/value), not `RedisTemplate<String, Student>`. This keeps Redis storage human-readable but requires manual JSON serialization/deserialization via `ObjectMapper`.
- The adapter holds a reference to `StudentLookupRepo` **and** `StringRedisTemplate` — it's doing double duty as both a Redis port and a JPA coordinator. This means the adapter is not a pure Redis adapter; it owns the persistence flow for the create path.
- `@Value("${app.cache.expiration}")` injects the TTL at construction time via a long-typed parameter.
- **Import anomaly:** `import tools.jackson.databind.ObjectMapper` — this is Jackson 3.x's new package name (Jackson moved from `com.fasterxml.jackson` to `tools.jackson` in 3.x, which ships with Spring Boot 4). This is correct for Spring Boot 4 but will break if the project is ever downgraded to Spring Boot 3.

**Risk / edge cases:**
- If Redis is unavailable at startup, `StringRedisTemplate` itself won't fail — but any call to `opsForValue().get(...)` will throw a `RedisConnectionFailureException`. There is no circuit-breaker or fallback; a Redis outage will make the entire read path fail even though H2 is healthy.
- `mapper.readValue` / `mapper.writeValueAsString` are called without try/catch. Jackson 3.x throws unchecked exceptions for these, but if `Student` ever gains a non-serializable field, it will throw at runtime.
- TTL is set once on write; there is no cache refresh on read. A hot entry will expire and cause a cache miss exactly once.

**Verification:** Integration test hitting `/student/{id}` twice to observe cache-hit log line on the second call.

---

### `bb1bfa8` — Add service layer and REST controller

**Intent:** Wire the service orchestration layer and expose HTTP endpoints.

**Files touched:**
| File | Reason |
|---|---|
| `StudentLookupService.java` | Orchestrates repo + adapter |
| `StudentLookupController.java` | REST layer (had bugs — fixed in `91f26c2`) |

**Key code changes (pre-refactor state):**

```java
// Bug 1: @Controller instead of @RestController
@Controller
public class StudentLookupController { ... }

// Bug 2: duplicate constructor parameter
public StudentLookupService(
    StudentLookupRepo studentLookupRepo,
    StudentLookupRedisAdapter studentLookupRedisAdapter,  // ← unused
    StudentLookupRedisAdapter redis) {                    // ← actually used
    ...
}

// Bug 3: client ID passed through
Student newStudent = new Student(student.getId(), student.getFirstName(), ...);
```

**Architecture notes:**
- `getAllStudents()` calls `studentLookupRepo.findAll()` **directly**, bypassing the Redis adapter entirely. The list endpoint has no caching — only single-record lookups are cached.
- `getStudentById()` delegates to `redis.findById()` which handles cache-aside internally.
- The service does not validate input. `createStudent` will happily accept a `Student` with null fields.

**Risk / edge cases:**
- `@Controller` bug: Spring MVC would attempt to resolve a view name from the `ResponseEntity` return type, likely throwing `javax.servlet.ServletException: No ViewResolver` or returning HTTP 406. The app would be broken for JSON clients until `91f26c2`.

---

### `c76a3ae` — Configure H2, JPA, and Redis properties

**Intent:** Wire all runtime infrastructure via `application.properties`.

**Files touched:**
| File | Reason |
|---|---|
| `src/main/resources/application.properties` | Full datasource, JPA, Redis, and cache config |

**Key config values:**
```properties
spring.datasource.url=jdbc:h2:mem:studentdb
spring.jpa.hibernate.ddl-auto=create-drop
spring.h2.console.enabled=true
spring.data.redis.host=localhost
spring.data.redis.port=6379
app.cache.expiration=120
```

**Risk / edge cases:**
- `create-drop` destroys the schema on application shutdown. All H2 data is lost between restarts. Acceptable for dev/demo; **never** use in production.
- `spring.h2.console.enabled=true` exposes the H2 web console at `/h2-console` with no authentication. Fine for local dev; a security risk on any networked deployment.
- `spring.jpa.open-in-view=false` is correctly set — avoids the lazy-load anti-pattern by keeping transactions out of the web layer.
- No `spring.data.redis.password` — assumes a local, unsecured Redis instance.

---

### `be002cf` + `956cdc7` — README documentation

**Intent:** Document architecture, setup instructions, API endpoints, and caching behavior with code snippets and log examples.

**Files touched:** `README.md`

**Notes:** The README is detailed and accurate. It includes the ASCII flow diagram, the cache-aside lifecycle, and example log output. No code changes — docs only.

---

### `91f26c2` — **Refactor: REST API structure and student creation logic** ← primary commit

**Intent:** Fix two bugs and clean up one code smell introduced in `bb1bfa8`.

**Files touched:**
| File | Change |
|---|---|
| `StudentLookupController.java` | `@Controller` → `@RestController`; `POST /students` → `POST /student`; wildcard import |
| `StudentLookupService.java` | Remove duplicate constructor param; stop passing client ID to Student constructor |

**Key code changes:**

```java
// BEFORE
@Controller
public class StudentLookupController {
// AFTER
@RestController
public class StudentLookupController {
```
`@RestController` = `@Controller` + `@ResponseBody` on every method. Without it, Spring MVC tried to resolve view names and the app could not serve JSON.

```java
// BEFORE — endpoint
@PostMapping("/students")

// AFTER — endpoint
@PostMapping("/student")
```
Singular resource path for a create-one operation. Aligns with REST convention (`POST /student` creates one student).

```java
// BEFORE — duplicate DI param (Spring would fail to wire or silently pick one)
public StudentLookupService(StudentLookupRepo repo,
    StudentLookupRedisAdapter studentLookupRedisAdapter,  // unused
    StudentLookupRedisAdapter redis) { ... }

// AFTER — clean single param
public StudentLookupService(StudentLookupRepo repo,
    StudentLookupRedisAdapter redis) { ... }
```
Spring's constructor injection with two parameters of the same type would require `@Qualifier` to disambiguate. The duplicate was likely a copy-paste error.

```java
// BEFORE — passes client-supplied ID through
Student newStudent = new Student(student.getId(), student.getFirstName(), ...);

// AFTER — always generates a fresh server-side UUID
Student newStudent = new Student(student.getFirstName(), student.getLastName(), student.getCollegeMajor());
```
This is the most significant **behavior change** in the entire refactor: the API now ignores any `id` field the client sends in the POST body. The server always generates a new UUID. This is the correct approach for a POST-to-create endpoint.

**Architecture notes:**
- `@RestController` removes the layering violation where the web framework config (MVC view resolution) was conflicting with the API contract.
- The endpoint rename (`/students` → `/student`) is a **breaking change** for any existing client. Worth noting in a changelog or migration note if this API has consumers.

**Risk / edge cases:**
- The refactored `createStudent` returns `ResponseEntity.ok(student)` where `student` is the *request body object* (not `newStudent`). The response will not contain the generated UUID — the caller gets back the object they sent, with no `id`. This is a subtle bug: the service creates `newStudent` with a UUID but the controller returns the original `student` which has `id = null`.
- No `@RequestMapping` base path on the controller — all paths are root-relative. Fine for now; harder to version later.

---

## End-to-End Critical Flow

### Request → Response: `POST /student`

```
Client  POST /student  {"firstName":"Leo","lastName":"Penrose","collegeMajor":"CS"}
  └─ StudentLookupController.createStudent(@RequestBody student)
       └─ StudentLookupService.createStudent(student)
            └─ new Student(firstName, lastName, collegeMajor)   ← UUID generated here
            └─ StudentLookupRedisAdapter.createNewStudent(newStudent)
                 ├─ StudentLookupRepo.save(newStudent)           ← H2 write
                 └─ StringRedisTemplate.set("student:<uuid>", json, 120s) ← Redis write
  └─ ResponseEntity.ok(student)  ← ⚠ returns original request body (id=null), not newStudent
```

### Request → Response: `GET /student/{id}`

```
Client  GET /student/abc-123
  └─ StudentLookupController.getStudentById("abc-123")
       └─ StudentLookupService.getStudentById("abc-123")
            └─ StudentLookupRedisAdapter.findById("abc-123")
                 ├─ StringRedisTemplate.get("student:abc-123")
                 │    ├─ HIT  → deserialize JSON → return Student
                 │    └─ MISS → StudentLookupRepo.findById("abc-123")
                 │               └─ H2 query
                 │               └─ ifPresent: set("student:abc-123", json, 120s)
                 └─ return Optional<Student>
       └─ orElse(null)
  └─ ResponseEntity.ok(student) or ResponseEntity.notFound()
```

### Request → Response: `GET /students`

```
Client  GET /students
  └─ StudentLookupController.getAllStudents()
       └─ StudentLookupService.getAllStudents()
            └─ StudentLookupRepo.findAll()  ← ⚠ no Redis cache — always hits H2
  └─ ResponseEntity.ok(List<Student>)
```

---

## Why This Design Is Better Than the "Before"

| Before | After |
|---|---|
| `@Controller` — app broken for JSON responses | `@RestController` — JSON serialization works correctly |
| Service constructor took same type twice — DI ambiguity | Clean single-param constructor |
| Client could inject arbitrary UUID via POST body | Server always generates UUID in `Student` constructor |
| Verbose import block (4 separate `@*Mapping` imports) | Single wildcard `org.springframework.web.bind.annotation.*` |

---

## Dependencies Added / Removed

No changes to `pom.xml` in this refactor. All dependencies were established in `e79529a`:

| Dependency | Role |
|---|---|
| `spring-boot-starter-web` | REST controller, MVC |
| `spring-boot-starter-data-jpa` | JPA/Hibernate, `JpaRepository` |
| `spring-boot-starter-data-redis` | `StringRedisTemplate`, Lettuce client |
| `com.h2database:h2` (runtime) | In-memory database |
| `spring-boot-starter-test` | Test context |

---

## Layering Violations

All classes live in a **single flat package** (`com.penrose.studentlookupredis`). There are no sub-packages for `domain`, `application`, `infrastructure`, or `api`. As a result:

- `Student` is annotated with `@Entity` (JPA infrastructure concern) and used as the API DTO — domain, persistence, and transport are all collapsed into one class.
- `StudentLookupRedisAdapter` imports and directly uses `StudentLookupRepo` — the Redis infrastructure adapter is coupled to the JPA infrastructure adapter. Neither is behind an interface.
- `StudentLookupService` imports `StudentLookupRedisAdapter` by concrete class name — the service depends on infrastructure, not on a port/interface.

These are acceptable coupling choices for a demo/learning project, but they would need to be unwound before this pattern could be considered production-ready.

---

## Suggested Micro-Fixes

1. **Return `newStudent` from the controller, not `student`**
   ```java
   // In StudentLookupController.createStudent:
   Student created = studentLookupService.createStudent(student);
   return ResponseEntity.ok(created);
   // And change createStudent to return Student instead of void
   ```
   Without this, the POST response never includes the generated UUID.

2. **HTTP 201 instead of 200 for create**
   ```java
   return ResponseEntity.status(HttpStatus.CREATED).body(created);
   ```

3. **Remove the 4-arg `Student` constructor or make it package-private** — it bypasses UUID generation and is now unused. Leaving it as `public` invites accidental misuse.

4. **Add a cache guard for `getAllStudents`** — the list endpoint always hits H2. For small student sets this is fine; at scale it should cache a list or be documented as intentionally uncached.

5. **Disable H2 console in `application-prod.properties`** — or gate it on `spring.profiles.active=dev`.

---

## Tests

### What exists
- `StudentLookUpRedisApplicationTests.contextLoads()` — verifies the Spring context starts. This test will **fail** if Redis is not running on `localhost:6379` because `StringRedisTemplate` is auto-configured at startup and Lettuce will attempt to connect eagerly.

### What should be added

1. **`StudentLookupRedisAdapterTest` (unit test with mocks)**
   ```
   Given: redis.get("student:abc") returns null (cache miss)
   When:  findById("abc") is called
   Then:  studentLookupRepo.findById("abc") is called once
   And:   redis.set("student:abc", ...) is called with the result
   ```

2. **Cache-hit test**
   ```
   Given: redis.get("student:abc") returns valid JSON
   When:  findById("abc") is called
   Then:  studentLookupRepo.findById("abc") is NEVER called
   And:   returned Student matches the JSON
   ```

3. **`StudentLookupServiceTest` — createStudent ID generation**
   ```
   Given: POST body has id = "client-supplied-id"
   When:  createStudent is called
   Then:  the Student saved to the adapter has a different, non-null UUID
   ```

4. **Controller integration test (`@WebMvcTest` + mocked service)**
   ```
   POST /student with valid body → 200 (or 201 after fix) with body
   GET /students → 200 with list
   GET /student/{id} with known id → 200
   GET /student/{id} with unknown id → 404
   ```

5. **Redis integration test (`@SpringBootTest` + embedded Redis or Testcontainers)**
   ```
   Test the full cache-aside cycle end-to-end with a real Redis instance
   ```

### Commands to run
```bash
# Run all tests
mvn test

# Run only the application tests
mvn test -Dtest=StudentLookUpRedisApplicationTests

# Run with Redis available (required for context load)
# Start Redis first: redis-server
mvn test

# Compile only (no Redis required)
mvn compile
```

---

## Lessons Learned

1. **`@Controller` vs `@RestController` matters immediately.** Using `@Controller` without `@ResponseBody` causes MVC to look for a Thymeleaf/FreeMarker view, not write JSON. The app was silently broken until `91f26c2`. Always use `@RestController` for pure REST APIs.

2. **Spring constructor injection with duplicate types requires `@Qualifier`.** The pre-refactor `StudentLookupService` constructor accepted `StudentLookupRedisAdapter` twice — Spring would throw `NoUniqueBeanDefinitionException` or silently inject the same bean twice. This kind of bug is easy to miss in code review.

3. **The 3-arg `Student` constructor is where ID ownership is established.** Deliberately routing `createStudent` through `new Student(firstName, lastName, major)` (not the 4-arg version) is a design decision, not just an implementation detail. Document it.

4. **Cache-aside and write-through are not symmetric across all endpoints.** `GET /student/{id}` uses Redis; `GET /students` does not. This asymmetry should be explicit in the design — either cache the list or add a comment explaining why it's intentionally uncached.

5. **Flat package structures make layering violations invisible.** When everything is in `com.penrose.studentlookupredis`, imports look clean but the coupling between domain, service, and infrastructure is unchecked. Sub-packages + package-private visibility enforce boundaries cheaply.

6. **H2 `create-drop` is a footgun if left in a shared config file.** It should only ever live in a `dev`-profile properties file. A future contributor running `mvn spring-boot:run` against a real database will lose all data on shutdown.

7. **Returning the request body object instead of the persisted object is a subtle but real bug.** The POST response for `/student` silently omits the generated UUID. Users have no way to look up the student they just created without a separate `GET /students` call.

---

## Action Items

### Immediate (today)
- Fix `createStudent` to return the server-generated `Student` (with UUID) in the response body
- Change `POST /student` response status to `201 Created`
- Add `@Qualifier` or simplify the constructor — the duplicate was removed, but audit for similar issues

### Short-term hardening (this week)
- Write the 5 unit/integration tests listed above
- Move H2/console config to a `dev` profile (`application-dev.properties`)
- Add a `@ControllerAdvice` for consistent error responses (currently 404 returns an empty body)
- Extract an interface (`StudentLookupPort`) that `StudentLookupRedisAdapter` implements, and have `StudentLookupService` depend on the interface, not the concrete class
- Add Redis connection failure handling (e.g., catch `RedisConnectionFailureException` in the adapter and fall back to DB)

### Strategic refactors (later)
- Introduce sub-packages: `domain/`, `application/`, `infrastructure/redis/`, `infrastructure/persistence/`, `api/`
- Decouple `Student` (domain) from the JPA `@Entity` using a separate `StudentEntity` record for persistence mapping
- Add a request DTO (`CreateStudentRequest`) so the API contract is not tied to the domain model
- Consider Testcontainers for Redis in CI so `contextLoads()` passes without a local Redis installation
- Add Spring Cache abstraction (`@Cacheable`, `@CachePut`) as an alternative to manual Redis template calls — evaluate trade-offs against the current explicit adapter approach
