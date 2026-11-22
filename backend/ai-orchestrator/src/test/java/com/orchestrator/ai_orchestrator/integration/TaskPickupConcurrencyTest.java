package com.orchestrator.ai_orchestrator.integration;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.common.AgentTypes;
import com.orchestrator.ai_orchestrator.planner.domain.PlannedTask;
import com.orchestrator.ai_orchestrator.planner.infrastructure.PlannedTaskRepository;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResult;
import com.orchestrator.ai_orchestrator.resultstore.domain.TaskResultStatus;
import com.orchestrator.ai_orchestrator.resultstore.infrastructure.TaskResultRepository;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the claim at the heart of the executor: when many workers ask for work at the same
 * moment, each task is handed to exactly one of them, and a job's tasks come out in order.
 *
 * <p>The background poller is switched off (very long delays), so ONLY this test claims tasks.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) // close the context with its container
@SpringBootTest(properties = {
        "gemini.api.key=test-key",
        "tavily.api.key=test-key",
        "orchestrator.executor.initial-delay-ms=3600000",
        "orchestrator.executor.poll-interval-ms=3600000",
        "orchestrator.recovery.interval-ms=3600000"
})
class TaskPickupConcurrencyTest extends AbstractPostgresTest {

    private static final String AGENT_TYPE = AgentTypes.WRITER_AGENT;

    @Autowired private JobRepository jobRepository;
    @Autowired private PlannedTaskRepository plannedTaskRepository;
    @Autowired private TaskQueueRepository taskQueueRepository;
    @Autowired private TaskResultRepository taskResultRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyTheDatabase() {
        // CASCADE also empties every table that points at jobs (tasks, queue, results ...).
        jdbcTemplate.execute("TRUNCATE TABLE jobs CASCADE");
    }

    @Test
    void everyTaskIsClaimedExactlyOnceWhenEightThreadsCompete() throws Exception {
        int jobCount = 30;
        for (int i = 0; i < jobCount; i++) {
            createJobWithTasks("single-task-job-" + i, 1); // one task each, all claimable at once
        }

        // Each thread keeps claiming until the queue is empty.
        List<QueuedTask> claimed = runConcurrently(8, () -> {
            List<QueuedTask> mine = new ArrayList<>();
            Optional<QueuedTask> next;
            while ((next = taskQueueRepository.atomicPickup(AGENT_TYPE, Instant.now())).isPresent()) {
                mine.add(next.get());
            }
            return mine;
        });

        Set<UUID> distinctIds = ConcurrentHashMap.newKeySet();
        claimed.forEach(task -> distinctIds.add(task.getId()));

        assertEquals(jobCount, claimed.size(), "every task must be claimed");
        assertEquals(jobCount, distinctIds.size(), "no task may be claimed twice");
        assertEquals(jobCount, taskQueueRepository.countByStatus(QueuedTaskStatus.PROCESSING),
                "the database must show every task as PROCESSING");
        assertEquals(0, taskQueueRepository.countByStatus(QueuedTaskStatus.PENDING));
    }

    @Test
    void tasksOfOneJobComeOutInPlanOrderAndAFailedTaskDoesNotBlockTheRest() throws Exception {
        Map<UUID, Integer> sequenceByPlannedTaskId = createJobWithTasks("ordered-job", 5);

        for (int round = 1; round <= 5; round++) {
            // Eight threads each try once. Only the task whose turn it is may be handed out.
            List<QueuedTask> claimed = runConcurrently(8, () -> {
                Optional<QueuedTask> task = taskQueueRepository.atomicPickup(AGENT_TYPE, Instant.now());
                return task.map(List::of).orElseGet(List::of);
            });

            assertEquals(1, claimed.size(), "round " + round + ": exactly one task may be claimed");
            QueuedTask task = claimed.get(0);
            assertEquals(round, (int) sequenceByPlannedTaskId.get(task.getPlannedTaskId()),
                    "round " + round + ": tasks must come out in plan order");

            // Settle the task. Round 3 FAILS on purpose: a failed task still counts as settled,
            // so the job must carry on with task 4.
            TaskResultStatus outcome = round == 3 ? TaskResultStatus.FAILED : TaskResultStatus.COMPLETED;
            Instant now = Instant.now();
            taskResultRepository.save(TaskResult.builder()
                    .jobId(task.getJobId())
                    .plannedTaskId(task.getPlannedTaskId())
                    .status(outcome)
                    .resultContent(outcome == TaskResultStatus.COMPLETED ? "ok" : null)
                    .failureReason(outcome == TaskResultStatus.FAILED ? "failed on purpose" : null)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        assertTrue(taskQueueRepository.atomicPickup(AGENT_TYPE, Instant.now()).isEmpty(),
                "nothing is left to claim after the fifth task");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Creates a job in EXECUTING with {@code taskCount} planned tasks (sequence 1..n) and one
     * PENDING queue entry per task.
     *
     * @return planned task id -> sequence number, in plan order
     */
    private Map<UUID, Integer> createJobWithTasks(String goal, int taskCount) {
        Instant now = Instant.now();
        Job job = jobRepository.save(Job.builder()
                .userId("test-user")
                .goal(goal)
                .status(JobStatus.EXECUTING)
                .createdAt(now)
                .updatedAt(now)
                .build());

        Map<UUID, Integer> sequenceByPlannedTaskId = new LinkedHashMap<>();
        for (int sequence = 1; sequence <= taskCount; sequence++) {
            PlannedTask planned = plannedTaskRepository.save(PlannedTask.builder()
                    .jobId(job.getId())
                    .sequenceNumber(sequence)
                    .taskDescription("task " + sequence + " of " + goal)
                    .requiredAgentType(AGENT_TYPE)
                    .createdAt(now)
                    .build());
            taskQueueRepository.save(QueuedTask.builder()
                    .jobId(job.getId())
                    .plannedTaskId(planned.getId())
                    .requiredAgentType(AGENT_TYPE)
                    .status(QueuedTaskStatus.PENDING)
                    .retryCount(0)
                    .queuedAt(now)
                    .build());
            sequenceByPlannedTaskId.put(planned.getId(), sequence);
        }
        return sequenceByPlannedTaskId;
    }

    /**
     * Runs {@code work} on {@code threads} threads that all start at the same instant (a start
     * gate), and returns everything they claimed. The gate matters: without it the first
     * thread could finish before the others begin, and nothing would compete.
     */
    private List<QueuedTask> runConcurrently(int threads, Callable<List<QueuedTask>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<List<QueuedTask>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return work.call();
                }));
            }
            startGate.countDown(); // Release all threads together.

            List<QueuedTask> all = new ArrayList<>();
            for (Future<List<QueuedTask>> future : futures) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }
}