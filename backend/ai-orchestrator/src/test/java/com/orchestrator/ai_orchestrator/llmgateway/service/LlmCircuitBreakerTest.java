package com.orchestrator.ai_orchestrator.llmgateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCircuitBreakerTest {

    /** A clock that only moves when the test says so, so no test ever sleeps. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
    }

    private ManualClock clock;
    private LlmCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        breaker = new LlmCircuitBreaker(3, Duration.ofSeconds(60), clock);
    }

    @Test
    void staysClosedBelowTheThreshold() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquirePermission());
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void opensAtTheThresholdAndRefusesCalls() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertEquals(LlmCircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.isOpen());
        assertFalse(breaker.tryAcquirePermission());
        assertTrue(breaker.secondsUntilRetry() > 0);
    }

    @Test
    void afterTheCooldownExactlyOneProbeIsAllowed() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advanceSeconds(59);
        assertFalse(breaker.tryAcquirePermission());

        clock.advanceSeconds(1);
        assertFalse(breaker.isOpen());
        assertTrue(breaker.tryAcquirePermission());   // the probe
        assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());  // a second caller is refused
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advanceSeconds(60);
        assertTrue(breaker.tryAcquirePermission());
        breaker.recordSuccess();
        assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquirePermission());
    }

    @Test
    void aFailedProbeReopensForAnotherFullCooldown() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advanceSeconds(60);
        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(LlmCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());

        clock.advanceSeconds(60);
        assertTrue(breaker.tryAcquirePermission());
    }

    @Test
    void aLostProbeIsReplacedAfterAnotherCooldown() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advanceSeconds(60);
        assertTrue(breaker.tryAcquirePermission());   // probe starts, never reports back
        clock.advanceSeconds(30);
        assertFalse(breaker.tryAcquirePermission());
        clock.advanceSeconds(30);
        assertTrue(breaker.tryAcquirePermission());   // replaced: the breaker cannot get stuck
    }
}