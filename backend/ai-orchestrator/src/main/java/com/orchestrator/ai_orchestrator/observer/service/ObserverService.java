package com.orchestrator.ai_orchestrator.observer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrator.ai_orchestrator.observer.domain.SystemEvent;
import com.orchestrator.ai_orchestrator.observer.infrastructure.SystemEventRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records system events (the audit trail of what each component did).
 *
 * <p>{@link #emit} returns immediately. The event is built on the calling thread, so its
 * timestamp is the moment it happened, and is written to the database by ONE background thread.
 * Consequences:
 * <ul>
 *   <li>A slow database never slows a job down.</li>
 *   <li>Events are written in the order they were emitted (single writer).</li>
 *   <li>An event is written in its own transaction, so it survives a rollback of the
 *       caller's transaction. A failed routing attempt keeps its ROUTING_STARTED event.</li>
 *   <li>The waiting line is bounded. If the writer falls {@code queue-capacity} events behind,
 *       new events are dropped and counted. Losing a log line is acceptable; blocking or
 *       crashing the pipeline for one is not.</li>
 * </ul>
 * On shutdown, queued events are given up to 10 seconds to be written.
 */
@Slf4j
@Service
public class ObserverService {

    private static final long SHUTDOWN_FLUSH_SECONDS = 10;
    private static final long LOG_EVERY_N_DROPS = 100;

    private final SystemEventRepository systemEventRepository;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor writer;
    private final AtomicLong droppedEvents = new AtomicLong();

    public ObserverService(SystemEventRepository systemEventRepository,
                           ObjectMapper objectMapper,
                           @Value("${orchestrator.observer.queue-capacity:10000}") int queueCapacity) {
        this.systemEventRepository = systemEventRepository;
        this.objectMapper = objectMapper;
        // 1 thread, a bounded waiting line, and "refuse when full" (AbortPolicy) so emit() never blocks.
        this.writer = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "observer-writer");
                    thread.setDaemon(true); // Never keeps the JVM alive on its own.
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void emit(String sourceComponent, String eventType, UUID jobId, Map<String, Object> payload) {
        SystemEvent event;
        try {
            // Serialised NOW, on the caller's thread, so a map changed later cannot alter the event.
            event = SystemEvent.builder()
                    .sourceComponent(sourceComponent)
                    .eventType(eventType)
                    .jobId(jobId)
                    .payload(objectMapper.writeValueAsString(payload))
                    .createdAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("Observer could not build event source={} type={} jobId={}",
                    sourceComponent, eventType, jobId, e);
            return;
        }

        try {
            writer.execute(() -> persist(event));
        } catch (RejectedExecutionException e) {
            long dropped = droppedEvents.incrementAndGet();
            if (dropped == 1 || dropped % LOG_EVERY_N_DROPS == 0) {
                log.warn("Observer queue full or stopped; {} event(s) dropped so far (latest: {} {})",
                        dropped, sourceComponent, eventType);
            }
        }
    }

    /** How many events were dropped because the writer could not keep up. Useful for health checks. */
    public long droppedEventCount() {
        return droppedEvents.get();
    }

    /** Runs on the writer thread. A failed insert is logged and skipped; it must never stop the writer. */
    private void persist(SystemEvent event) {
        try {
            systemEventRepository.save(event);
        } catch (Exception e) {
            log.error("Observer failed to save event source={} type={} jobId={}",
                    event.getSourceComponent(), event.getEventType(), event.getJobId(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown(); // Accept no new events; let the queued ones finish.
        try {
            if (!writer.awaitTermination(SHUTDOWN_FLUSH_SECONDS, TimeUnit.SECONDS)) {
                int lost = writer.shutdownNow().size();
                log.warn("Observer shutdown timed out; {} queued event(s) were not written", lost);
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}