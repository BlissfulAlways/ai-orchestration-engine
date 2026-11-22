package com.orchestrator.ai_orchestrator.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Starts a throwaway Postgres 15 in Docker for the tests of one class, and points the
 * application at it. The schema is created by the same Flyway migrations as in production,
 * so the tests run against the real schema, including its constraints and seed data.
 *
 * <p>Why a real Postgres and not an in-memory database: the task pickup uses
 * {@code FOR UPDATE SKIP LOCKED} and JSONB, which in-memory databases do not behave like.
 */
@Testcontainers
abstract class AbstractPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    /** Runs when the Spring context is built, after the container has started. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}