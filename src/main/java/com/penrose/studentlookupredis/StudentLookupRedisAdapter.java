package com.penrose.studentlookupredis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
public class StudentLookupRedisAdapter {
    private static final Logger log = LoggerFactory.getLogger(StudentLookupRedisAdapter.class);
    private static final String KEY_PREFIX = "student:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final StudentLookupRepo studentLookupRepo;
    private final Duration ttl;

    public StudentLookupRedisAdapter(StringRedisTemplate redis, ObjectMapper mapper, StudentLookupRepo studentLookupRepo,@Value("${app.cache.expiration}") long ttlSeconds) {
        this.redis = redis;
        this.mapper = mapper;
        this.studentLookupRepo = studentLookupRepo;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        log.info("Initialized StudentLookupRedisAdapter with TTL of {} seconds", ttlSeconds);
    }

    /* ============================
     *  READ ONE  (cache-aside)
     *  Redis concept: GET
     * ============================ */

    public Optional<Student> findById(String id){
        String key = KEY_PREFIX + id;

        String json = redis.opsForValue().get(key);

        if(json != null){
            log.info("Cache hit for key: {}", key);
            return Optional.of(mapper.readValue(json, Student.class));
        }

        log.info("Cache miss for key: {}", key);
        Optional<Student> studentFromDb = studentLookupRepo.findById(id);
        log.info("Queried database for student with ID: {}", id);

        studentFromDb.ifPresent( toDo -> {
            redis.opsForValue().set(key, mapper.writeValueAsString(toDo), ttl);
            log.info("Cached student with key: {} for {} seconds", key, ttl.getSeconds());
        });

        return studentFromDb;

    }

    /* ============================
     *  CREATE
     *  Redis concept: SET (write-through)
     * ============================ */

    public void createNewStudent(Student student){
        studentLookupRepo.save(student);
        String key = KEY_PREFIX + student.getId();
        redis.opsForValue().set(key, mapper.writeValueAsString(student), ttl);
        log.info("Created new student with ID: {} and cached with key: {}", student.getId(), key);
    }
}
