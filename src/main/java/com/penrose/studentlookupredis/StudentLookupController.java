package com.penrose.studentlookupredis;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Controller
public class StudentLookupController {

  private final StudentLookupService studentLookupService;

  public StudentLookupController(StudentLookupService studentLookupService) {
    this.studentLookupService = studentLookupService;
  }

  @PostMapping("/students")
  public ResponseEntity<Student> createStudent(@RequestBody Student student) {
    studentLookupService.createStudent(student);
    return ResponseEntity.ok(student);
  }

  @GetMapping("/students")
  public ResponseEntity<List<Student>> getAllStudents() {
    List<Student> students = studentLookupService.getAllStudents();
    return ResponseEntity.ok(students);
  }

  @GetMapping("/student/{id}")
  public ResponseEntity<Student> getStudentById(@PathVariable String id) {
    Student student = studentLookupService.getStudentById(id);
    if (student != null) {
      return ResponseEntity.ok(student);
    } else {
      return ResponseEntity.notFound().build();
    }
  }
}
