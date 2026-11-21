package com.orchestrator.ai_orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * One shared HTTP client for the whole application.
 *
 * <p>A client owns a connection pool. Creating one per request throws the pool away, so every
 * call pays for a new TCP and TLS handshake. Sharing one lets connections be reused.
 *
 * <p>Redirects are NOT followed (the default). Following them would let a page send the
 * agent to an address the model never chose; that needs the URL checks planned for Phase 5.
 */
@Configuration
public class HttpClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }
}