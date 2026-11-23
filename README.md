# AI Orchestration Engine

[![CI](https://github.com/BlissfulAlways/ai-orchestration-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/BlissfulAlways/ai-orchestration-engine/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)
![PostgreSQL 15](https://img.shields.io/badge/PostgreSQL-15-blue)

A backend service that turns a goal written in plain English into a finished answer. An LLM
plans the work, agents carry out each task using web search and page fetching, and a final
step combines the results. The engineering focus is **reliability**: the system keeps its
promises when the model provider runs out of quota, when the process crashes mid-job, when
workers die, and when tasks fail.

## At a glance

| | |
|---|---|
| **Problem** | LLM workflows are slow (minutes), expensive, rate-limited and unreliable. A naive implementation loses work on a crash, double-runs tasks, and burns quota when the provider is down. |
| **Approach** | A durable job pipeline on PostgreSQL: every step is persisted, every state change is atomic, every step can be safely repeated. |
| **Stack** | Java 21 (virtual threads), Spring Boot 3.5, PostgreSQL 15, Flyway, Gemini (native function calling), Tavily, Testcontainers |
| **Quality gate** | 49 automated tests, including concurrency and crash-recovery tests on a real PostgreSQL; CI runs them on every push |

## How it works

```
POST /api/v1/jobs {"goal": "..."}  ->  202 Accepted + jobId           (client is never blocked)

  RECEIVED -> PLANNING -> ROUTING -> EXECUTING -> AGGREGATING -> COMPLETED
                                                                 COMPLETED_WITH_ERRORS
                                                                 FAILED

  PLANNING     the LLM returns a task list as validated JSON (one correction round if invalid)
  ROUTING      every task is matched to an agent; all tasks are queued, or none
  EXECUTING    a bounded worker pool claims tasks whose dependencies are done; each task runs an agent loop
               that may call web_search and fetch_page
  AGGREGATING  exactly one thread wins the claim and has the LLM write the final answer
```

```
 client --> API gateway --> planner --> router --> task queue (PostgreSQL) --> worker pool
            (auth, limits)                         FOR UPDATE SKIP LOCKED     (virtual threads)
                                                                                   |
 final answer <-- aggregator <-- result store <-- agent loop --> tools (SSRF-protected) |
                                                       |                           |
                                                  LLM gateway (retries, circuit breaker)
```

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Reliability

| Failure | Behaviour |
|---|---|
| Gemini out of quota or down | Retry with exponential backoff and jitter, then a **circuit breaker** stops all calls; queued work waits and loses nothing; one probe call after the cooldown |
| Process crashes mid-job | A recovery scheduler resumes the job from the step it reached, without repeating finished work |
| A worker dies | Tasks send heartbeats; silent tasks are re-queued, or failed after 3 attempts |
| Two workers want the same task | One SQL statement claims it (`FOR UPDATE SKIP LOCKED`); verified by a test: 30 tasks, 8 threads, 0 duplicates |
| One task fails permanently | The job finishes as `COMPLETED_WITH_ERRORS` and the answer names what is missing |

The full list is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#failure-model).

## Security

API-key authentication (keys stored as SHA-256 hashes), per-user job ownership (another
user's job answers 404), per-user concurrency limit, input size limits, SSRF protection on the
fetch tool (private, loopback and link-local addresses blocked on every redirect hop), CORS
allow-list, and no stack traces in error responses. Threat model:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#security-model).

## Quick start

Requirements: Java 21, Docker.

```bash
# 1. Database (choose any password; it is only used by this local container)
export DB_PASSWORD=change-me-local-only
docker run --name pg -e POSTGRES_USER=orchestrator -e POSTGRES_PASSWORD=$DB_PASSWORD \
  -e POSTGRES_DB=orchestratordb -p 5432:5432 -d postgres:15

# 2. Secrets (keep them out of the repository)
export GEMINI_API_KEY=...
export TAVILY_API_KEY=...
export ADMIN_TOKEN=a-long-random-value-of-16-or-more-characters

# 3. Run (Flyway creates the schema on first start)
cd backend/ai-orchestrator && ./mvnw spring-boot:run
```

```bash
# Create a key for a user, submit a job, watch it
KEY=$(curl -s -X POST localhost:8080/api/v1/admin/keys \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"userId":"alice"}' | grep -o '"apiKey":"[^"]*"' | cut -d'"' -f4)

JOB=$(curl -s -X POST localhost:8080/api/v1/jobs -H "X-API-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Compare PostgreSQL and MongoDB for a chat application"}' \
  | grep -o '"jobId":"[^"]*"' | cut -d'"' -f4)

curl -s -H "X-API-Key: $KEY" localhost:8080/api/v1/jobs/$JOB/progress
curl -s -H "X-API-Key: $KEY" localhost:8080/api/v1/jobs/$JOB
```

## Web UI

A React interface shows live progress and the step-by-step agent trace.

    cd frontend/ai-orchestrator-ui && npm install && npm run dev

Open the printed address, paste an API key (created in the quick start above) and submit a goal. In development the Vite server forwards `/api` calls to the backend on port 8080, so no CORS setup is needed.

## API

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/v1/jobs` | user key | Submit a goal. `202` with `jobId`; `429` with `Retry-After` if too many active jobs |
| `GET /api/v1/jobs` | user key | Your jobs, newest first (`page`, `size`) |
| `GET /api/v1/jobs/{id}` | user key | Status, final answer, failure reason |
| `GET /api/v1/jobs/{id}/progress` | user key | Task counts and percent complete; cheap enough to poll |
| `GET /api/v1/jobs/{id}/trace` | user key | Every task and every agent step, for debugging |
| `POST /api/v1/admin/keys` / `DELETE /api/v1/admin/keys/{id}` | admin token | Create and revoke API keys |
| `GET /actuator/health` | public | Up or down; queue depth and circuit state shown to admin only |

Errors use one JSON shape ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details).

## Testing

```bash
cd backend/ai-orchestrator && ./mvnw verify     # needs Docker; CI runs the same command
```

| Layer | What it proves |
|---|---|
| Concurrency (real PostgreSQL via Testcontainers) | 8 threads, 30 tasks: each claimed exactly once; a task is released only when its dependencies are settled; independent tasks are claimed together; a failed task does not block unrelated work |
| Pipeline (real PostgreSQL, fake LLM) | goal to final answer; partial failure; recovery of an abandoned job (planner called exactly once) |
| Unit | circuit breaker (controllable clock), SSRF rules, API-key handling, entity equality |

The pipeline tests replace the LLM with a scripted fake, so the suite needs no API key, no
quota and no network.

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Goals, components, state machine, data model, concurrency, failure model, security model |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why PostgreSQL as the queue, why compare-and-set, and other decisions with the alternatives considered |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) | Diagnosing stuck jobs, quota outages, pool exhaustion |

## Status and roadmap

Version 1 of the API. Known limits and planned work:

1. **Scheduling.** Independent tasks run in parallel, but there is no per-job priority or cost budget yet.
2. **Identity.** API keys and one admin token; a company deployment would use OAuth 2 / OIDC.
3. **Load testing.** Correctness under concurrency is tested; throughput and latency under
   load have not been measured yet.
4. **Metrics.** Health and job-tagged logs exist; Prometheus metrics (queue depth, task
   latency, breaker state) are not exported yet.
5. **Egress control.** The URL check cannot stop DNS rebinding alone; production needs a
   network-level egress policy.
