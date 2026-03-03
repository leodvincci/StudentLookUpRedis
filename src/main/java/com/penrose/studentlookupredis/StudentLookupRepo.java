package com.penrose.studentlookupredis;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentLookupRepo extends JpaRepository<Student, String> {}
