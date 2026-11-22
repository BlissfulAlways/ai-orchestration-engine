package com.orchestrator.ai_orchestrator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Who may call what.
 *
 * <pre>
 *   /actuator/health, /actuator/info   anyone (load balancers need them); the health DETAILS
 *                                      are shown only to ADMIN (see application.yaml)
 *   /error                             anyone (Spring's internal error page; without this an
 *                                      authenticated 404 would turn into a 401)
 *   /api/v1/admin/**                   ADMIN only
 *   /api/v1/**                         USER only
 *   everything else                    must be authenticated (so unknown paths answer 401)
 * </pre>
 *
 * <p>The API is stateless: no session, no cookie. Each request carries its own key, so CSRF
 * protection (which defends cookie-based sessions) has nothing to protect and is switched off.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ApiKeyService apiKeyService,
                                                   ObjectMapper objectMapper,
                                                   @Value("${orchestrator.security.admin-token:}") String adminToken)
            throws Exception {

        if (adminToken == null || adminToken.isBlank()) {
            log.warn("ADMIN_TOKEN is not set: the key-management endpoints are disabled "
                    + "and no API key can be created.");
        } else if (adminToken.length() < 16) {
            throw new IllegalStateException("ADMIN_TOKEN must be at least 16 characters long");
        }

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults()) // uses the rules from CorsConfig
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // CORS preflight
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("USER")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED,
                                        "Missing or invalid credentials"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeProblem(response, objectMapper, HttpStatus.FORBIDDEN,
                                        "Not allowed to use this endpoint")))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService, adminToken),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Writes the same small JSON error shape the rest of the API uses (RFC 9457). */
    private static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper,
                                     HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ProblemDetail.forStatusAndDetail(status, detail)));
    }
}