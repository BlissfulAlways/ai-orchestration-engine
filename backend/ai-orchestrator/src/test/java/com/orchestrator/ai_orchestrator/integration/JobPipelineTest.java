package com.orchestrator.ai_orchestrator.integration;

import com.orchestrator.ai_orchestrator.apigateway.domain.Job;
import com.orchestrator.ai_orchestrator.apigateway.domain.JobStatus;
import com.orchestrator.ai_orchestrator.apigateway.infrastructure.JobRepository;
import com.orchestrator.ai_orchestrator.apigateway.service.JobProgressService;
import com.orchestrator.ai_orchestrator.apigateway.service.JobQueryService;
import com.orchestrator.ai_orchestrator.apigateway.service.JobService;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmException;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmGatewayService;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmRequest;
import com.orchestrator.ai_orchestrator.llmgateway.service.LlmResponse;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTask;
import com.orchestrator.ai_orchestrator.taskqueue.domain.QueuedTaskStatus;
import com.orchestrator.ai_orchestrator.taskqueue.infrastructure.TaskQueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs whole jobs through the real application (planner, router, executor, aggregator, recovery,
 * real Postgres) with a FAKE Gemini. No API key, no quota, no network: the answers are scripted.
 *
 * <p>The fake behaves by the goal text (dependsOn lists task numbers):
 * <pre>
 *   scenario-happy     Alpha []        Beta [1]
 *   scenario-partial   Alpha []        Boom [1]        Boom is rejected for good
 *   scenario-parallel  Alpha []        Beta []         Gamma [1,2]
 *   scenario-cascade   Alpha []        Boom []         Gamma [2]       Gamma must never reach the model
 *   scenario-recovery  same plan as happy; the job is inserted as if it had been abandoned
 * </pre>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "gemini.api.key=test-key",
        "tavily.api.key=test-key",
        "orchestrator.executor.poll-interval-ms=200",
        "orchestrator.recovery.interval-ms=500",
        "orchestrator.recovery.stale-minutes=1"
})
class JobPipelineTest extends AbstractPostgresTest {

    private static final Set<JobStatus> FINAL_STATUSES =
            Set.of(JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_ERRORS, JobStatus.FAILED);

    /** Replaces the real Gemini gateway with a stand-in whose answers this test scripts. */
    @MockitoBean private LlmGatewayService llm;

    @Autowired private JobService jobService;
    @Autowired private JobQueryService jobQueryService;
    @Autowired private JobProgressService jobProgressService;
    @Autowired private JobRepository jobRepository;
    @Autowired private TaskQueueRepository taskQueueRepository;

    /** How many fake agent calls are in progress right now, and the highest that number reached. */
    private final AtomicInteger activeAgents = new AtomicInteger();
    private final AtomicInteger maxActiveAgents = new AtomicInteger();

    @BeforeEach
    void scriptTheFakeModel() {
        when(llm.generate(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            String prompt = promptOf(request);

            return switch (request.callingComponent()) {
                case "TASK_PLANNER" -> reply(planFor(prompt));
                case "AGENT_EXECUTOR" -> agentReply(prompt);
                default -> throw new IllegalStateException("unexpected component " + request.callingComponent());
            };
        });

        // The aggregator uses call(...). Echoing its prompt lets the tests see what it was given.
        when(llm.call(any(), eq("RESULT_AGGREGATOR"), any(), any()))
                .thenAnswer(invocation -> "FINAL ANSWER :: " + invocation.getArgument(3));
    }

    @Test
    void aJobRunsFromGoalToFinalAnswer() {
        UUID jobId = jobService.submitJob("test-user", "scenario-happy");

        awaitFinalStatus(jobId);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.COMPLETED, job.getStatus(), "failureReason=" + job.getFailureReason());

        String answer = jobQueryService.findJob(jobId, "test-user").orElseThrow().finalAnswer();
        assertNotNull(answer);
        assertTrue(answer.contains("done alpha"), "the aggregator must receive task 1's result");
        // "saw alpha: true" proves task 2 ran AFTER task 1 and was given task 1's result.
        assertTrue(answer.contains("done beta (saw alpha: true)"), "answer was: " + answer);

        List<QueuedTask> queue = taskQueueRepository.findByJobId(jobId);
        assertEquals(2, queue.size());
        queue.forEach(task -> {
            assertEquals(QueuedTaskStatus.COMPLETED, task.getStatus());
            assertEquals(0, task.getRetryCount(), "no task should have needed a retry");
        });
    }

    @Test
    void independentTasksReallyRunInParallel() {
        UUID jobId = jobService.submitJob("test-user", "scenario-parallel");

        awaitFinalStatus(jobId);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.COMPLETED, job.getStatus(), "failureReason=" + job.getFailureReason());

        // Alpha and Beta need nothing, and each fake call takes 300 ms, so if they run in
        // parallel the fake sees two calls in progress at the same moment.
        assertTrue(maxActiveAgents.get() >= 2,
                "Alpha and Beta should have overlapped, but at most " + maxActiveAgents.get() + " ran at once");

        // Gamma depends on both, so it must have been given both results.
        String answer = jobQueryService.findJob(jobId, "test-user").orElseThrow().finalAnswer();
        assertTrue(answer.contains("done gamma (saw alpha: true, saw beta: true)"), "answer was: " + answer);
    }

    @Test
    void aTaskThatFailsForGoodGivesPartialResultsNotAFailedJob() {
        UUID jobId = jobService.submitJob("test-user", "scenario-partial");

        awaitFinalStatus(jobId);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.COMPLETED_WITH_ERRORS, job.getStatus(), "failureReason=" + job.getFailureReason());
        assertNotNull(job.getFailureReason());
        assertTrue(job.getFailureReason().contains("1 of 2 task(s) failed"), job.getFailureReason());

        String answer = jobQueryService.findJob(jobId, "test-user").orElseThrow().finalAnswer();
        assertTrue(answer.contains("done alpha"), "the successful task's result is kept");
        assertTrue(answer.contains("Task Boom"), "the answer must name the task that failed");

        List<QueuedTask> queue = taskQueueRepository.findByJobId(jobId);
        assertEquals(1, queue.stream().filter(t -> t.getStatus() == QueuedTaskStatus.COMPLETED).count());
        assertEquals(1, queue.stream().filter(t -> t.getStatus() == QueuedTaskStatus.FAILED).count());
    }

    @Test
    void aFailedDependencyFailsItsDependentsWithoutCallingTheModel() {
        UUID jobId = jobService.submitJob("test-user", "scenario-cascade");

        awaitFinalStatus(jobId);

        Job job = jobRepository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.COMPLETED_WITH_ERRORS, job.getStatus(), "failureReason=" + job.getFailureReason());
        assertTrue(job.getFailureReason().contains("2 of 3 task(s) failed"), job.getFailureReason());

        String answer = jobQueryService.findJob(jobId, "test-user").orElseThrow().finalAnswer();
        assertTrue(answer.contains("DEPENDENCY_FAILED"), "the reason must say why Gamma did not run: " + answer);

        // Gamma depends on Boom, which failed: it must never have cost a model call.
        verify(llm, never()).generate(argThat(request ->
                "AGENT_EXECUTOR".equals(request.callingComponent()) && promptOf(request).contains("Task Gamma")));
    }

    @Test
    void anAbandonedJobIsFoundAndFinishedByRecovery() {
        // A job that was accepted 5 minutes ago and then nothing happened, as after a crash:
        // it sits in RECEIVED and no pipeline is running for it.
        Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
        Job abandoned = jobRepository.save(Job.builder()
                .userId("test-user")
                .goal("scenario-recovery")
                .status(JobStatus.RECEIVED)
                .createdAt(fiveMinutesAgo)
                .updatedAt(fiveMinutesAgo)
                .build());

        awaitFinalStatus(abandoned.getId());

        Job recovered = jobRepository.findById(abandoned.getId()).orElseThrow();
        assertEquals(JobStatus.COMPLETED, recovered.getStatus(), "failureReason=" + recovered.getFailureReason());

        // Recovery must not plan twice: the planner is asked exactly once for this job.
        verify(llm, times(1)).generate(argThat(request ->
                "TASK_PLANNER".equals(request.callingComponent())
                        && abandoned.getId().equals(request.jobId())));
    }

    @Test
    void progressAddsUpAndIsPrivateToTheOwner() {
        UUID jobId = jobService.submitJob("test-user", "scenario-happy");
        awaitFinalStatus(jobId);

        JobProgressService.Progress progress =
                jobProgressService.getProgress(jobId, "test-user").orElseThrow();

        assertEquals("COMPLETED", progress.jobStatus());
        assertEquals(2, progress.totalTasks());
        assertEquals(2, progress.completed());
        assertEquals(0, progress.failed());
        assertEquals(0, progress.running());
        assertEquals(0, progress.pending());
        assertEquals(100, progress.percentComplete());
        assertTrue(jobProgressService.getProgress(jobId, "someone-else").isEmpty(),
                "another user must not see this job's progress");
    }

    // ------------------------------------------------------------------ the fake model

    /**
     * The fake agent. It holds every call for 300 ms so that calls running in parallel really
     * overlap, and records how many ran at once.
     *
     * <p>Later tasks must be checked FIRST: their prompts also contain the earlier tasks'
     * descriptions and results (as dependency context).
     */
    private LlmResponse agentReply(String prompt) throws InterruptedException {
        int nowActive = activeAgents.incrementAndGet();
        maxActiveAgents.accumulateAndGet(nowActive, Math::max);
        try {
            Thread.sleep(300);

            if (prompt.contains("Task Boom")) {
                throw new LlmException("fake provider rejected this task", false);
            }
            if (prompt.contains("Task Gamma")) {
                return reply("done gamma (saw alpha: " + prompt.contains("done alpha")
                        + ", saw beta: " + prompt.contains("done beta") + ")");
            }
            if (prompt.contains("Task Beta")) {
                return reply("done beta (saw alpha: " + prompt.contains("done alpha") + ")");
            }
            return reply("done alpha");
        } finally {
            activeAgents.decrementAndGet();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void awaitFinalStatus(UUID jobId) {
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> FINAL_STATUSES.contains(jobRepository.findById(jobId).orElseThrow().getStatus()));
    }

    private static String promptOf(LlmRequest request) {
        return request.contents().get(0).path("parts").get(0).path("text").asText();
    }

    private static LlmResponse reply(String text) {
        return new LlmResponse(text, List.of(), null, 5, "STOP");
    }

    /** The plan the fake planner returns for each scenario. dependsOn lists task numbers. */
    private static String planFor(String prompt) {
        String task2;
        String task3 = null;
        if (prompt.contains("scenario-parallel")) {
            task2 = task("Task Beta: write the second part", "[]");
            task3 = task("Task Gamma: combine both parts", "[1,2]");
        } else if (prompt.contains("scenario-cascade")) {
            task2 = task("Task Boom: write the second part", "[]");
            task3 = task("Task Gamma: build on the second part", "[2]");
        } else if (prompt.contains("scenario-partial")) {
            task2 = task("Task Boom: write the second part", "[1]");
        } else {
            task2 = task("Task Beta: write the second part", "[1]");
        }
        String task1 = task("Task Alpha: write the first part", "[]");
        return "[" + task1 + "," + task2 + (task3 == null ? "" : "," + task3) + "]";
    }

    private static String task(String description, String dependsOn) {
        return "{\"taskDescription\":\"" + description + "\",\"requiredAgentType\":\"WRITER_AGENT\","
                + "\"dependsOn\":" + dependsOn + "}";
    }
}