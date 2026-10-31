package com.paisatrack.modules.people;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.paisatrack.platform.persistence.AuditableEntity;

/** Someone the user lends to or borrows from. Contact details are optional. */
@Entity
@Table(name = "people")
public class Person extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "notes", length = 500)
    private String notes;

    protected Person() {
    }

    Person(UUID userId, String name) {
        this.userId = userId;
        this.name = name.strip();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getNotes() {
        return notes;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setPhone(String phone) {
        this.phone = phone;
    }

    void setEmail(String email) {
        this.email = email;
    }

    void setNotes(String notes) {
        this.notes = notes;
    }
}
