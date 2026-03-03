# StudentLookUpRedis

A Spring Boot REST API for managing student records, using **Redis** as a caching layer in front of an **H2** in-memory database. Demonstrates the **cache-aside** (lazy-loading) and **write-through** caching patterns.

## Architecture

```
Client  →  Controller  →  Service  →  RedisAdapter  →  Redis (cache)
                                            ↓
                                        JPA Repo  →  H2 (database)
```

- **Cache-aside (reads):** Check Redis first. On a miss, query H2, then populate the cache.
- **Write-through (writes):** Write to H2 and Redis together so subsequent reads are instant cache hits.
- **TTL expiration:** Cached entries expire after a configurable duration (default: 2 minutes).

## Tech Stack

- Java 21
- Spring Boot 4.0.3
- Spring Data JPA (Hibernate)
- Spring Data Redis
- H2 Database (in-memory, runtime)
- Maven

## Prerequisites

- **Java 21+**
- **Maven 3.9+** (or use the included Maven wrapper)
- **Redis** running on `localhost:6379`

### Installing Redis

**Fedora/RHEL:**
```bash
sudo dnf install redis
sudo systemctl start redis
```

**Ubuntu/Debian:**
```bash
sudo apt install redis-server
sudo systemctl start redis
```

**macOS (Homebrew):**
```bash
brew install redis
brew services start redis
```

**Docker:**
```bash
docker run -d --name redis -p 6379:6379 redis
```

Verify Redis is running:
```bash
redis-cli ping
# Expected output: PONG
```

## Running the Application

```bash
# Clone the repository
git clone https://github.com/leodvincci/StudentLookUpRedis.git
cd StudentLookUpRedis

# Build and run
./mvnw spring-boot:run
```

The application starts on **http://localhost:8080**.

## API Endpoints

### Create a Student

**Request:**
```bash
curl -X POST http://localhost:8080/students \
  -H "Content-Type: application/json" \
  -d '{"firstName": "John", "lastName": "Doe", "collegeMajor": "Computer Science"}'
```

**Response:** `200 OK`
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "firstName": "John",
  "lastName": "Doe",
  "collegeMajor": "Computer Science"
}
```

**Application logs:**
```
INFO  StudentLookupRedisAdapter : Created new student with ID: a1b2c3d4-e5f6-7890-abcd-ef1234567890 and cached with key: student:a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

---

### Get Student by ID (cache miss — first request)

**Request:**
```bash
curl http://localhost:8080/student/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Response:** `200 OK`
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "firstName": "John",
  "lastName": "Doe",
  "collegeMajor": "Computer Science"
}
```

**Application logs (cache miss):**
```
INFO  StudentLookupRedisAdapter : Cache miss for key: student:a1b2c3d4-e5f6-7890-abcd-ef1234567890
INFO  StudentLookupRedisAdapter : Queried database for student with ID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Hibernate: select s1_0.id,s1_0.college_major,s1_0.first_name,s1_0.last_name from student s1_0 where s1_0.id=?
INFO  StudentLookupRedisAdapter : Cached student with key: student:a1b2c3d4-e5f6-7890-abcd-ef1234567890 for 120 seconds
INFO  StudentLookupService      : Student found: John Doe
```

### Get Student by ID (cache hit — subsequent request)

**Request:**
```bash
curl http://localhost:8080/student/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Response:** `200 OK` (same as above)

**Application logs (cache hit — no SQL query):**
```
INFO  StudentLookupRedisAdapter : Cache hit for key: student:a1b2c3d4-e5f6-7890-abcd-ef1234567890
INFO  StudentLookupService      : Student found: John Doe
```

Notice there is no `Hibernate: select ...` log — the response came entirely from Redis.

### Get Student by ID (not found)

**Request:**
```bash
curl http://localhost:8080/student/nonexistent-id
```

**Response:** `404 Not Found` (empty body)

**Application logs:**
```
INFO  StudentLookupRedisAdapter : Cache miss for key: student:nonexistent-id
INFO  StudentLookupRedisAdapter : Queried database for student with ID: nonexistent-id
Hibernate: select s1_0.id,s1_0.college_major,s1_0.first_name,s1_0.last_name from student s1_0 where s1_0.id=?
WARN  StudentLookupService      : Student with ID nonexistent-id not found
```

---

### Get All Students

**Request:**
```bash
curl http://localhost:8080/students
```

**Response:** `200 OK`
```json
[
  {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "firstName": "John",
    "lastName": "Doe",
    "collegeMajor": "Computer Science"
  },
  {
    "id": "f9e8d7c6-b5a4-3210-fedc-ba0987654321",
    "firstName": "Jane",
    "lastName": "Smith",
    "collegeMajor": "Mathematics"
  }
]
```

**Application logs:**
```
Hibernate: select s1_0.id,s1_0.college_major,s1_0.first_name,s1_0.last_name from student s1_0
INFO  StudentLookupService : Retrieved 2 students
```

> **Note:** `GET /students` always queries the database directly — list endpoints bypass the cache.

## Key Code Examples

### Cache-Aside Read Pattern (RedisAdapter)

```java
public Optional<Student> findById(String id) {
    String key = KEY_PREFIX + id;

    // 1. Check cache first
    String json = redis.opsForValue().get(key);

    if (json != null) {
        log.info("Cache hit for key: {}", key);
        return Optional.of(mapper.readValue(json, Student.class));
    }

    // 2. Cache miss — query the database
    log.info("Cache miss for key: {}", key);
    Optional<Student> studentFromDb = studentLookupRepo.findById(id);

    // 3. Populate cache for next time
    studentFromDb.ifPresent(student -> {
        redis.opsForValue().set(key, mapper.writeValueAsString(student), ttl);
        log.info("Cached student with key: {} for {} seconds", key, ttl.getSeconds());
    });

    return studentFromDb;
}
```

### Write-Through Pattern (RedisAdapter)

```java
public void createNewStudent(Student student) {
    // 1. Write to database
    studentLookupRepo.save(student);

    // 2. Write to cache so subsequent reads are instant hits
    String key = KEY_PREFIX + student.getId();
    redis.opsForValue().set(key, mapper.writeValueAsString(student), ttl);
    log.info("Created new student with ID: {} and cached with key: {}", student.getId(), key);
}
```

### Student Entity

```java
@Entity
public class Student {
    @Id
    private String id;
    private String firstName;
    private String lastName;
    private String collegeMajor;

    public Student(String firstName, String lastName, String collegeMajor) {
        this.id = String.valueOf(UUID.randomUUID());
        this.firstName = firstName;
        this.lastName = lastName;
        this.collegeMajor = collegeMajor;
    }
}
```

## Caching Flow Summary

```
GET /student/{id}
        │
        ▼
  ┌──────────┐     hit     ┌───────────┐
  │  Redis   │ ──────────→ │  Return   │
  │  lookup  │             │  cached   │
  └──────────┘             └───────────┘
        │ miss
        ▼
  ┌──────────┐             ┌───────────┐
  │  Query   │ ──────────→ │  Cache in │
  │  H2 DB   │             │  Redis    │
  └──────────┘             └───────────┘
        │                        │
        ▼                        ▼
  ┌──────────────────────────────────┐
  │         Return to client         │
  └──────────────────────────────────┘
```

## H2 Console

The H2 database console is enabled for development at **http://localhost:8080/h2-console**.

| Setting     | Value                  |
|-------------|------------------------|
| JDBC URL    | `jdbc:h2:mem:studentdb`|
| Username    | `sa`                   |
| Password    | *(empty)*              |

## Configuration

Key properties in `src/main/resources/application.properties`:

| Property                          | Default               | Description                        |
|-----------------------------------|-----------------------|------------------------------------|
| `spring.data.redis.host`          | `localhost`           | Redis server host                  |
| `spring.data.redis.port`          | `6379`                | Redis server port                  |
| `spring.data.redis.timeout`       | `2000ms`              | Redis connection timeout           |
| `app.cache.expiration`            | `120`                 | Cache TTL in seconds (2 minutes)   |
| `spring.jpa.hibernate.ddl-auto`   | `create-drop`         | DDL strategy (dev only)            |
| `spring.h2.console.enabled`       | `true`                | Enable H2 web console (dev only)   |

## Running Tests

```bash
./mvnw test
```
