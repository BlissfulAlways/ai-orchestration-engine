package com.orchestrator.ai_orchestrator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Deletes old rows from the two tables that only ever grow: event_logs (audit events) and
 * llm_call_logs (full prompts and responses, which are large).
 *
 * <p>Runs once a night. Rows are deleted in batches of {@code batch-size}; each batch is its
 * own short transaction, so a large backlog never locks the table for long.
 *
 * <p>Jobs, tasks, results and final answers are NOT touched: they are the product, not logs.
 */
@Slf4j
@Service
public class LogRetentionService {

    /** Constant table names, never user input, so building the SQL text from them is safe. */
    private static final List<String> LOG_TABLES = List.of("event_logs", "llm_call_logs");

    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;
    private final int batchSize;

    public LogRetentionService(JdbcTemplate jdbcTemplate,
                               @Value("${orchestrator.retention.days:30}") int retentionDays,
                               @Value("${orchestrator.retention.batch-size:5000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(100, batchSize);
    }

    /** Default: every day at 03:30 server time. Spring cron fields: sec min hour day month weekday. */
    @Scheduled(cron = "${orchestrator.retention.cron:0 30 3 * * *}")
    public void purgeOldLogs() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        for (String table : LOG_TABLES) {
            try {
                purge(table, cutoff);
            } catch (Exception e) {
                // One failing table must not stop the other from being cleaned.
                log.error("Log retention failed for table={}", table, e);
            }
        }
    }

    private void purge(String table, Instant cutoff) {
        long total = 0;
        int deleted;
        do {
            deleted = jdbcTemplate.update(
                    "DELETE FROM " + table + " WHERE id IN ("
                            + "SELECT id FROM " + table + " WHERE created_at < ? "
                            + "ORDER BY created_at LIMIT ?)",
                    Timestamp.from(cutoff), batchSize);
            total += deleted;
        } while (deleted == batchSize); // A full batch means there may be more.

        log.info("Log retention table={} deleted={} olderThanDays={}", table, total, retentionDays);
    }
}