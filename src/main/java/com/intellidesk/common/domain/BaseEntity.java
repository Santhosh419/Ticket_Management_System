package com.intellidesk.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Common identity + audit columns shared by every entity.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>Surrogate keys ({@code IDENTITY}) keep foreign keys stable even when natural
 *       values (email, ticket number) change.</li>
 *   <li>createdAt / updatedAt are filled by Spring Data JPA auditing, never by callers,
 *       so audit data cannot be forged through API payloads.</li>
 *   <li>Instant is stored as UTC ({@code hibernate.jdbc.time_zone=UTC}), avoiding the
 *       classic timezone drift problem between app servers and the database.</li>
 * </ul>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
