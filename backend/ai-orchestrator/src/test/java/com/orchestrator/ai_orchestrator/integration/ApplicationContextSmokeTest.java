package com.orchestrator.ai_orchestrator.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Smoke test: the whole application starts against a real, freshly migrated Postgres.
 * It catches wiring mistakes (a missing bean, a bad yaml key, a migration that fails)
 * before anything else runs.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "gemini.api.key=test-key",
        "tavily.api.key=test-key"
})
class ApplicationContextSmokeTest extends AbstractPostgresTest {

    @Test
    void contextLoads() {
        // No assertions needed: the test fails if the application cannot start.
    }
}
