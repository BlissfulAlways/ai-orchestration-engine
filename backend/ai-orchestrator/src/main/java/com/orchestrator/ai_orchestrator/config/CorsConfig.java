package com.orchestrator.ai_orchestrator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser access rules. Only the origins listed in {@code orchestrator.cors.allowed-origins}
 * may call the API from a web page. The old setting allowed every website on the internet.
 *
 * <p>CORS only restricts browsers. It is not a substitute for authentication (Phase 5C).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${orchestrator.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-API-Key")
                .allowCredentials(false)
                .maxAge(3600); // Browsers may cache the permission check for an hour.
    }
}