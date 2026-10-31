package com.paisatrack.modules.category;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.paisatrack.platform.persistence.AuditableEntity;

/** Top-level category ({@code parentId == null}) or subcategory. Transactions keep pointing here across renames. */
@Entity
@Table(name = "categories")
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "system_code", length = 40, updatable = false)
    private String systemCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private CategoryType type;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Category() {
    }

    Category(UUID userId, UUID parentId, CategoryType type, String name, int sortOrder) {
        this.userId = userId;
        this.parentId = parentId;
        this.type = type;
        this.name = name.strip();
        this.sortOrder = sortOrder;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public boolean isTopLevel() {
        return parentId == null;
    }

    public String getSystemCode() {
        return systemCode;
    }

    public CategoryType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    void rename(String name) {
        this.name = name.strip();
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    void setActive(boolean active) {
        this.active = active;
    }
}
