package com.penrose.studentlookupredis;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

@Entity
public class Student {
    @Id
    private String id;
    private String firstName;
    private String lastName;
    private String collegeMajor;

    public Student() {};

    public Student(String firstName, String lastName, String collegeMajor) {
        this.id = String.valueOf(UUID.randomUUID());
        this.firstName = firstName;
        this.lastName = lastName;
        this.collegeMajor = collegeMajor;
    }

    public Student(String id, String firstName, String lastName, String collegeMajor) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.collegeMajor = collegeMajor;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getCollegeMajor() {
        return collegeMajor;
    }

    public void setCollegeMajor(String collegeMajor) {
        this.collegeMajor = collegeMajor;
    }

    @Override
    public String toString() {
        return "Student{" +
                "id='" + id + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", collegeMajor='" + collegeMajor + '\'' +
                '}';
    }
}
