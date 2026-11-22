package com.orchestrator.ai_orchestrator.llmgateway.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops the application from calling Gemini while Gemini keeps failing.
 *
 * <p>Without it, every task and every job keeps sending requests and waiting through retries,
 * even when the provider is out of quota. With it, after a run of failures the breaker "opens"
 * and calls fail at once, without any network traffic, until a cooldown has passed.
 *
 * <pre>
 *                  N failures in a row
 *      CLOSED ------------------------------> OPEN
 *     (calls flow)                      (calls refused at once)
 *         ^                                    |
 *         |                                    | cooldown has passed
 *         |  the probe call succeeds           v
 *         +--------------------------------- HALF_OPEN
 *                                          (ONE probe call allowed)
 *                                              |
 *                    the probe call fails -----+--> back to OPEN
 * </pre>
 *
 * <p>What counts as a failure: a timeout, a network error, HTTP 408, 429 or 5xx, which all
 * mean "the provider cannot serve us right now". An HTTP 400 (bad request) or a blocked prompt
 * is NOT a failure: the provider answered, so it is healthy.
 *
 * <p>All methods are {@code synchronized}: state changes are tiny and fast, and many threads
 * (workers, pipelines) share one breaker.
 */
@Component
public class LlmCircuitBreaker {

    /** The three states. A plain enum so health checks and logs can print it. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock; // Injected so tests can move time without sleeping.

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private Instant openedAt = Instant.MIN;
    private boolean probeInFlight = false;
    private Instant probeStartedAt = Instant.MIN;

    /** Used by Spring. */
    @Autowired
    public LlmCircuitBreaker(
            @Value("${gemini.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${gemini.circuit.cooldown-seconds:60}") int cooldownSeconds) {
        this(failureThreshold, Duration.ofSeconds(cooldownSeconds), Clock.systemUTC());
    }

    /** Used by tests, with a clock they control. */
    public LlmCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /**
     * Asks "may I make one call to Gemini now?". Call this before EVERY attempt, including retries.
     * A caller that gets {@code true} must report the outcome with {@link #recordSuccess()}
     * or {@link #recordFailure()}.
     */
    public synchronized boolean tryAcquirePermission() {
        Instant now = clock.instant();
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (now.isBefore(openedAt.plus(cooldown))) {
                    return false; // Still cooling down.
                }
                state = State.HALF_OPEN; // Cooldown over: let exactly one probe through.
                probeInFlight = true;
                probeStartedAt = now;
                return true;
            case HALF_OPEN:
            default:
                // A probe is already out. If it vanished (its thread was interrupted and never
                // reported), allow a new one after one more cooldown, so the breaker cannot
                // get stuck forever.
                boolean probeLost = probeInFlight && !now.isBefore(probeStartedAt.plus(cooldown));
                if (!probeInFlight || probeLost) {
                    probeInFlight = true;
                    probeStartedAt = now;
                    return true;
                }
                return false;
        }
    }

    /** Gemini answered normally (or with an error that is our fault, not its). */
    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeInFlight = false;
    }

    /** Gemini could not serve the call: timeout, network error, 429 or 5xx. */
    public synchronized void recordFailure() {
        Instant now = clock.instant();
        if (state == State.HALF_OPEN) {
            open(now); // The probe failed: wait another full cooldown.
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open(now);
        }
    }

    /**
     * True while calls are being refused. The task poller uses this to leave tasks waiting
     * in the queue instead of starting work that is certain to fail. Returns false once the
     * cooldown is over, so the next task can serve as the probe.
     */
    public synchronized boolean isOpen() {
        return state == State.OPEN && clock.instant().isBefore(openedAt.plus(cooldown));
    }

    public synchronized State state() {
        return state;
    }

    /** Whole seconds until a probe is allowed; 0 if calls are allowed now. */
    public synchronized long secondsUntilRetry() {
        if (state != State.OPEN) {
            return 0;
        }
        long remaining = Duration.between(clock.instant(), openedAt.plus(cooldown)).toSeconds();
        return Math.max(0, remaining + 1);
    }

    private void open(Instant now) {
        state = State.OPEN;
        openedAt = now;
        probeInFlight = false;
        consecutiveFailures = 0;
    }
}