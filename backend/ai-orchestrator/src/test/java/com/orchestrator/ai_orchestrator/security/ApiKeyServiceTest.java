package com.orchestrator.ai_orchestrator.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** No database and no Spring: the repository is replaced by a Mockito stand-in. */
class ApiKeyServiceTest {

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyService service = new ApiKeyService(repository);

    @Test
    void theRawKeyIsReturnedButOnlyItsHashIsStored() {
        when(repository.save(any(ApiKey.class))).thenAnswer(call -> {
            ApiKey key = call.getArgument(0);
            key.setId(UUID.randomUUID());
            return key;
        });

        ApiKeyService.IssuedKey issued = service.issue("alice", "laptop");

        ArgumentCaptor<ApiKey> stored = ArgumentCaptor.forClass(ApiKey.class);
        verify(repository).save(stored.capture());
        String storedHash = stored.getValue().getKeyHash();

        assertTrue(issued.apiKey().startsWith("ak_"));
        assertEquals(64, storedHash.length());
        assertNotEquals(issued.apiKey(), storedHash);
        assertEquals(ApiKeyService.sha256Hex(issued.apiKey()), storedHash);
        assertEquals("alice", stored.getValue().getUserId());
    }

    @Test
    void twoIssuedKeysAreDifferent() {
        when(repository.save(any(ApiKey.class))).thenAnswer(call -> {
            ApiKey key = call.getArgument(0);
            key.setId(UUID.randomUUID());
            return key;
        });
        assertNotEquals(service.issue("a", "").apiKey(), service.issue("a", "").apiKey());
    }

    @Test
    void authenticateLooksTheHashUpAndReturnsTheOwner() {
        String rawKey = "ak_some-long-enough-test-key";
        when(repository.findByKeyHashAndRevokedAtIsNull(ApiKeyService.sha256Hex(rawKey)))
                .thenReturn(Optional.of(ApiKey.builder().userId("alice").build()));

        assertEquals(Optional.of("alice"), service.authenticate(rawKey));
    }

    @Test
    void authenticateReturnsEmptyForAnUnknownKey() {
        when(repository.findByKeyHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), service.authenticate("ak_unknown-but-well-formed"));
    }

    @Test
    void malformedKeysAreRejectedWithoutTouchingTheDatabase() {
        assertEquals(Optional.empty(), service.authenticate(null));
        assertEquals(Optional.empty(), service.authenticate(""));
        assertEquals(Optional.empty(), service.authenticate("short"));
        assertEquals(Optional.empty(), service.authenticate("x".repeat(300)));
        verifyNoInteractions(repository);
    }
}