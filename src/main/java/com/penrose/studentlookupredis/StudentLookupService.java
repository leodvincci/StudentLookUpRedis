package com.penrose.studentlookupredis;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentLookupService {
    private final StudentLookupRepo studentLookupRepo;
    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StudentLookupService.class);
    private final StudentLookupRedisAdapter redis;

    public StudentLookupService(StudentLookupRepo studentLookupRepo, StudentLookupRedisAdapter redis) {
        this.studentLookupRepo = studentLookupRepo;
        this.redis = redis;
    }

    public void createStudent(Student student) {
        Student newStudent = new Student(student.getFirstName(), student.getLastName(), student.getCollegeMajor());
        redis.createNewStudent(newStudent);
    }

    public List<Student> getAllStudents() {
        List<Student> students = studentLookupRepo.findAll();
        logger.info("Retrieved {} students", students.size());
        return students;
    }

    public Student getStudentById(String id) {
        Student student = redis.findById(id).orElse(null);
        if (student != null) {
            logger.info("Student found: {} {}", student.getFirstName(), student.getLastName());
        } else {
            logger.warn("Student with ID {} not found", id);
        }
        return student;
    }
}
