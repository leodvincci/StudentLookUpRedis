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

```bash
curl -X POST http://localhost:8080/students \
  -H "Content-Type: application/json" \
  -d '{"firstName": "John", "lastName": "Doe", "collegeMajor": "Computer Science"}'
```

### Get All Students

```bash
curl http://localhost:8080/students
```

### Get Student by ID

```bash
curl http://localhost:8080/student/{id}
```

On the first call, you'll see a cache miss in the logs (fetches from H2, then caches in Redis). Subsequent calls within the TTL window return from cache directly.

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
