package com.orchestrator.ai_orchestrator.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Key management. Reachable only with the admin token (see {@link SecurityConfig}). */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/keys")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;

    public record CreateKeyRequest(
            @NotBlank(message = "userId cannot be blank")
            @Size(max = 100, message = "userId must be at most 100 characters")
            String userId,

            @Size(max = 100, message = "label must be at most 100 characters")
            String label
    ) {}

    /** Creates a key. The raw key is in this response and nowhere else, ever. */
    @PostMapping
    public ResponseEntity<Map<String, String>> createKey(@Valid @RequestBody CreateKeyRequest request) {
        ApiKeyService.IssuedKey issued = apiKeyService.issue(request.userId().trim(), request.label());
        log.info("API key created keyId={} userId={}", issued.keyId(), issued.userId());
        return ResponseEntity.status(201).body(Map.of(
                "keyId", issued.keyId().toString(),
                "userId", issued.userId(),
                "apiKey", issued.apiKey(),
                "warning", "Store this key now. It cannot be shown again."));
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revokeKey(@PathVariable UUID keyId) {
        if (!apiKeyService.revoke(keyId)) {
            return ResponseEntity.notFound().build();
        }
        log.info("API key revoked keyId={}", keyId);
        return ResponseEntity.noContent().build();
    }
}