# Architecture

## Goals and non-goals

**Goals**

- Never lose accepted work: a job submitted survives a crash and finishes.
- Never run a task twice at the same time, and never run a job's tasks out of order.
- Degrade gracefully when the model provider fails: stop wasting quota, wait, resume.
- Give the caller a useful result even when part of the job failed.
- Keep one user from reading another user's data or exhausting shared capacity.

**Non-goals (for now)**

- Horizontal scaling beyond what PostgreSQL's queue supports (see DECISIONS, 001).
- Streaming partial answers to the client.
- Multi-tenant billing or quotas beyond the per-user active-job limit.

## Components

Each package under `com.orchestrator.ai_orchestrator` is one responsibility.

| Package | Responsibility |
|---|---|
| `apigateway` | HTTP API, job submission, queries, progress, trace, the resumable pipeline, crash recovery |
| `security` | API-key authentication, roles, admin key management |
| `planner` | Asks the LLM for a task list as schema-constrained JSON; validates it; saves it all-or-nothing |
| `router` | Matches each task to a registered agent; queues all tasks or none; moves the job to EXECUTING |
| `taskqueue` | The queue table and the atomic claim query |
| `executor` | Worker pool, heartbeats, stuck-task rescue, the agent loop, tool execution |
| `llmgateway` | The only class that talks to Gemini: retries, concurrency limit, circuit breaker, call logging |
| `resultstore` | Per-task results (one per task, enforced by the database) |
| `aggregator` | Combines results into the final answer; exactly one caller wins the claim |
| `observer` | Asynchronous audit events with a bounded queue |
| `config` | Pools, schedulers, CORS, health indicator, log retention |
| `common` | Shared helpers: text, error messages, entity identity |

## Job state machine

```
RECEIVED --plan--> PLANNING --route--> ROUTING --queue tasks--> EXECUTING
                                                                    |
                                                          last task settles
                                                                    v
                                                              AGGREGATING
                                                                    |
                     +------------------+---------------------------+
                     v                  v                           v
                 COMPLETED   COMPLETED_WITH_ERRORS               FAILED
              (all tasks ok)  (some tasks failed)      (all tasks failed, or a stage failed)
```

Every arrow is a compare-and-set: `UPDATE jobs SET status = :new WHERE id = :id AND status = :expected`,
returning the number of rows changed. Zero means another thread got there first or the state
was unexpected, and the caller stops. This makes each transition happen exactly once even with
many threads or several application instances.

Who performs each transition:

| Transition | Performed by |
|---|---|
| (new) -> RECEIVED | `JobService.submitJob` |
| RECEIVED -> PLANNING -> ROUTING | `JobService` pipeline |
| ROUTING -> EXECUTING | `RouterService`, in the same transaction that queues the tasks |
| EXECUTING -> AGGREGATING | `AggregatorService`, by whichever thread settles the last task |
| AGGREGATING -> final status | `FinalAnswerCommitService`, atomically with saving the answer |
| any non-final -> FAILED | `JobRepository.markFailed` (never overwrites a final state) |

## Request lifecycle

1. `POST /api/v1/jobs`: the security filter identifies the caller from `X-API-Key`; the
   per-user active-job limit is checked; the job row is saved as RECEIVED; `202` is returned.
2. A virtual thread runs the pipeline: plan, then route. It is resumable: it reads the job's
   status and whether a plan exists, so it can be started again after an interruption.
3. A scheduler thread polls the queue every 2 s and hands tasks to a bounded worker pool
   (default 3). The poller never runs a task itself.
4. A worker runs the agent loop: ask the model, run the tools it requests, return the results,
   repeat until it answers in plain text (at most 10 steps; the last step has tools disabled
   to force an answer).
5. The result and the queue row change in one short transaction.
6. The thread that settles the last task triggers aggregation. One caller wins the claim, the
   model writes the final answer, and the answer and final status are saved together.

## Data model

| Table | Purpose |
|---|---|
| `jobs` | One row per job: owner, goal, status, final answer link, failure reason |
| `planned_tasks` | The plan: tasks, the agent type each needs, and `depends_on` (sequence numbers of the earlier tasks it needs) |
| `task_queue` | One entry per task: PENDING, PROCESSING, COMPLETED, FAILED; retry count; heartbeat |
| `task_results` | One result per task (unique on `planned_task_id`), so a repeated attempt cannot duplicate |
| `agent_execution_steps` | Every model thought, tool call and tool result: the trace |
| `final_answers` | The combined answer per job |
| `agent_registry`, `tool_registry` | Which agents and tools exist (seeded by migration) |
| `llm_call_logs` | Every model call: component, tokens, latency, status, job |
| `event_logs` | Audit events from every component |
| `api_keys` | Key owner and SHA-256 hash; revocation time |

Schema changes are versioned Flyway migrations (`db/migration/V1` ... `V17`). Hibernate never
changes the schema.

## Concurrency model

- **Claiming a task** is one statement: `UPDATE ... WHERE id = (SELECT ... FOR UPDATE OF q SKIP LOCKED LIMIT 1) RETURNING *`.
  The subselect also requires every task listed in the candidate's `depends_on` to have a settled result, so independent tasks of one job are released together and a dependent task waits for all of its dependencies. Locked rows are skipped, not waited for.
- **Threads:** virtual threads for pipelines and workers (cheap to block on I/O); a 6-thread
  scheduler so a slow scheduled method cannot block the rescuer; a semaphore bounds
  concurrent model calls (default 2) and is held only while a request is in flight, never
  during a retry wait.
- **Transactions are short.** No transaction and no database connection is held during a model
  call. Hikari leak detection (20 s) would flag a regression, and a test watches for
  connections left idle in a transaction.

## Failure model

| Failure | Detection | Response |
|---|---|---|
| Provider rate limit or outage | HTTP 408/429/5xx, timeouts, network errors | Retry up to 5 times: 2 s base, doubling, capped at 60 s, equal jitter, honouring the server's retry hint |
| Provider still failing | 5 failures in a row | Circuit breaker OPEN for 60 s; the poller starts no tasks; running tasks return to the queue without using a retry; then one probe call decides |
| Invalid plan from the model | Validation of JSON, agent types, task count | Tell the model what was wrong; one more attempt; then the job fails with the reason |
| Task fails permanently (blocked prompt, bad key) | Non-retryable error | Task marked FAILED at once; tasks that depend on it fail immediately with DEPENDENCY_FAILED, without a model call; unrelated tasks continue |
| Task fails temporarily | Retryable error | Back to PENDING, retry count + 1, up to 3 |
| Worker dies | No heartbeat for 120 s | Re-queued; failed after 3 attempts |
| Application crash | Job not updated for 10 minutes and no pipeline running here | Recovery resumes by status: re-plan if no plan, re-route, re-aggregate |
| Duplicate completion | Unique constraint on task results and final answers | Second writer is ignored |
| Slow shutdown | SIGTERM | Graceful: stop taking work, finish running requests (45 s), running tasks wait for rescue after restart |
| Audit event backlog | Bounded queue (10 000) | New events are dropped and counted, shown in health; the main work is never blocked |

## Security model

| Threat | Mitigation |
|---|---|
| Stolen database | API keys are stored only as SHA-256 hashes; keys are 256-bit random values |
| Guessing keys | 256 bits of randomness; constant-time comparison for the admin token |
| Reading another user's job | Ownership is part of every query; foreign jobs answer 404, hiding existence |
| Pretending to be another user | The user id comes from the key; any `userId` in the request body is ignored |
| Prompt injection from web pages | Tool output is labelled untrusted in the system prompt; it is never executed as instructions; the agent has only read-only tools |
| SSRF through the fetch tool | http/https only, ports 80/443, no credentials in URLs, every resolved address must be public, redirects followed by hand (max 3) with each hop re-checked |
| Resource exhaustion | Goal length limit (4 000), per-user active-job limit (5), bounded workers, page size caps, fetch size cap (500 KB) |
| Information leakage | No stack traces or exception messages in responses; unexpected errors return an error id that is searchable in the log |
| Cross-site abuse | CORS allow-list; stateless API with no cookies, so CSRF does not apply |
| Secret exposure | Keys come from environment variables; the application refuses to start without model keys |

Known gap: DNS rebinding between the address check and the connection; the fix is an egress
firewall at deployment level.

## Observability

- Every log line carries the job id (`[job=...]`); worker threads also carry the task id.
- Every model call is stored with tokens, latency, status and job id, so cost per job is one SQL query.
- Every agent step is stored and exposed through `GET /api/v1/jobs/{id}/trace`.
- `/actuator/health` reports queue depth, running tasks, dropped audit events and circuit state (admin only).
- Not yet: exported metrics and distributed tracing (roadmap).

## Scaling path

The design already allows several application instances against one database: claims use row
locks, transitions are compare-and-set, recovery claims jobs with a compare-and-set on
`updated_at`. The first limits you would meet are the provider's quota (shared by all
instances; the circuit breaker is per instance) and PostgreSQL write volume on the queue. See
DECISIONS 001 for the step after that.
