package com.orchestrator.ai_orchestrator.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates, checks and revokes API keys.
 *
 * <p>A key is 32 random bytes (256 bits) from a cryptographic random generator, shown to the
 * owner once and stored only as a SHA-256 hash. A fast hash is correct here: the key is
 * already random and long, so there is nothing to guess. (Passwords chosen by humans need a
 * deliberately slow hash such as bcrypt; random keys do not.)
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String KEY_PREFIX = "ak_"; // makes a leaked key easy to recognise
    private static final int MIN_KEY_LENGTH = 10;
    private static final int MAX_KEY_LENGTH = 200;

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** The only moment the raw key exists outside the owner's hands. */
    public record IssuedKey(UUID keyId, String userId, String apiKey) {}

    @Transactional
    public IssuedKey issue(String userId, String label) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
                .userId(userId)
                .label(label == null ? "" : label)
                .keyHash(sha256Hex(rawKey))
                .createdAt(Instant.now())
                .build());
        return new IssuedKey(saved.getId(), userId, rawKey);
    }

    /**
     * @return the owner's user id if the key exists and is not revoked, otherwise empty.
     *         Obviously malformed keys are rejected before touching the database.
     */
    @Transactional(readOnly = true)
    public Optional<String> authenticate(String rawKey) {
        if (rawKey == null || rawKey.length() < MIN_KEY_LENGTH || rawKey.length() > MAX_KEY_LENGTH) {
            return Optional.empty();
        }
        return apiKeyRepository.findByKeyHashAndRevokedAtIsNull(sha256Hex(rawKey))
                .map(ApiKey::getUserId);
    }

    @Transactional
    public boolean revoke(UUID keyId) {
        return apiKeyRepository.revoke(keyId, Instant.now()) > 0;
    }

    /** SHA-256 of the text as 64 lowercase hex characters. */
    public static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must provide SHA-256, so this cannot happen.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}