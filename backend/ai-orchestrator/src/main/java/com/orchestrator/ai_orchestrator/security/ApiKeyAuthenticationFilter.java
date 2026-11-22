package com.orchestrator.ai_orchestrator.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Reads the credentials from the request headers and, if they are valid, records who the
 * caller is for the rest of the request.
 *
 * <pre>
 *   X-Admin-Token: ...   equals the configured admin token  -> ROLE_ADMIN
 *   X-API-Key: ak_...    matches an active stored key       -> ROLE_USER, name = key owner
 *   neither, or invalid                                      -> no identity; the security rules
 *                                                               then answer 401
 * </pre>
 *
 * <p>This class is deliberately NOT a Spring bean: a filter bean is also registered with the
 * servlet container, runs outside the security chain, and its result is then overwritten.
 * {@link SecurityConfig} creates it and places it inside the chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final ApiKeyService apiKeyService;
    /** Hash of the configured admin token; null when no token is configured (admin disabled). */
    private final byte[] adminTokenHash;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, String adminToken) {
        this.apiKeyService = apiKeyService;
        this.adminTokenHash = (adminToken == null || adminToken.isBlank())
                ? null : sha256(adminToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication authentication = authenticate(request);
        if (authentication != null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private Authentication authenticate(HttpServletRequest request) {
        String adminToken = request.getHeader(ADMIN_TOKEN_HEADER);
        if (adminToken != null && adminTokenHash != null && matchesAdminToken(adminToken)) {
            return UsernamePasswordAuthenticationToken.authenticated(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        }

        Optional<String> userId = apiKeyService.authenticate(request.getHeader(API_KEY_HEADER));
        return userId.<Authentication>map(id -> UsernamePasswordAuthenticationToken.authenticated(
                        id, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))))
                .orElse(null);
    }

    /**
     * Compares the HASHES of both tokens in constant time. Comparing hashes means the input
     * length does not matter, and constant time means the time taken does not reveal how many
     * leading characters were right.
     */
    private boolean matchesAdminToken(String provided) {
        return MessageDigest.isEqual(sha256(provided), adminTokenHash);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}