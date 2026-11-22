package com.orchestrator.ai_orchestrator.security;

import com.orchestrator.ai_orchestrator.common.EntityIdentity;
import com.orchestrator.ai_orchestrator.common.Identifiable;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A stored API key: who owns it and the hash of its secret. Never holds the raw key. */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true) // the hash must never reach a log line
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "api_keys")
public class ApiKey implements Identifiable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    @ToString.Include
    private UUID id;

    @Column(name = "user_id", nullable = false)
    @ToString.Include
    private String userId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "key_hash", nullable = false, unique = true)
    private String keyHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Override
    public boolean equals(Object other) {
        return EntityIdentity.sameEntity(this, other);
    }

    @Override
    public int hashCode() {
        return EntityIdentity.hashOf(this);
    }
}