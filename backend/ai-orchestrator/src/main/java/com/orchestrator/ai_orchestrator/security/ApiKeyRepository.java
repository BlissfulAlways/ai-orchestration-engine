package com.orchestrator.ai_orchestrator.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /** The lookup done on every request: a key works only while it is not revoked. */
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    /** Revokes a key; returns 1 if it was active, 0 if it did not exist or was already revoked. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ApiKey k SET k.revokedAt = :now WHERE k.id = :id AND k.revokedAt IS NULL")
    int revoke(@Param("id") UUID id, @Param("now") Instant now);
}