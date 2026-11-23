# Design decisions

Each entry records the context, the decision, the alternatives considered, and what it costs.

---

## 001. PostgreSQL is the task queue

**Context.** Tasks must be claimed by exactly one worker, survive crashes, and keep their
order within a job. The same data (status, results) is also needed by the API.

**Decision.** A `task_queue` table, claimed with `FOR UPDATE SKIP LOCKED` in one statement.

**Alternatives.** A message broker (Kafka, SQS, RabbitMQ); Redis lists.

**Consequences.** One system to operate and back up. A claim and a result change in one
transaction, so there is no "message delivered but result lost" gap. Task dependencies are an SQL
condition instead of a partitioning scheme. Cost: queue throughput is bounded by PostgreSQL;
at much higher volume the next step is a broker for dispatch, keeping the database as the
source of truth.

---

## 002. State changes are compare-and-set in SQL

**Context.** Several threads (and possibly instances) can finish tasks, recover jobs and
aggregate at the same moment.

**Decision.** Every transition is `UPDATE ... WHERE status = :expected` and returns the rows
changed; the caller proceeds only when the answer is 1.

**Alternatives.** Application locks (`synchronized`, a lock map); pessimistic row locks held
across the work; optimistic locking with version columns.

**Consequences.** Correctness does not depend on how many threads or instances run. No lock is
held while slow work happens. Cost: callers must handle "0 rows changed" explicitly, which the
code does on every transition.

---

## 003. No transaction or database connection during an LLM call

**Context.** A model call can take seconds to minutes (retries included). The connection pool
has 15 connections.

**Decision.** Slow work runs outside transactions. Persistence is split into small
transactional services (`TaskOutcomeService`, `FinalAnswerCommitService`) that each do one
quick write.

**Alternatives.** One `@Transactional` method per task.

**Consequences.** Three workers cannot exhaust the pool by waiting on a model. The price is
that a crash between "model answered" and "result saved" repeats the model call; this is
accepted because results are idempotent (unique per task).

---

## 004. Native function calling instead of parsing tool requests from text

**Context.** The original agent loop looked for `TOOL_CALL:` markers in model text, which
could appear inside a normal answer.

**Decision.** Use Gemini's function-calling API: the model returns a structured request with a
tool name and JSON arguments. The planner uses a response schema, so its output is JSON by
construction, and is still validated.

**Alternatives.** Regex parsing; asking for JSON in the prompt only.

**Consequences.** No ambiguity between answer text and tool requests. Cost: tied to a
provider feature, mitigated by keeping all provider code in `llmgateway`.

---

## 005. A small, self-written circuit breaker

**Context.** When the provider's quota is used up, every task and every job would otherwise
retry for about 30 seconds each and consume more quota.

**Decision.** A 120-line breaker class (closed, open, half-open) with an injectable clock.

**Alternatives.** Resilience4j.

**Consequences.** Fully unit-testable without sleeping; one call site, so a library adds more
surface than it removes. A production team may reasonably prefer Resilience4j for its
metrics and ecosystem; replacing it touches only `LlmGatewayService`. The breaker is per
instance, not shared.

---

## 006. API keys, stored as SHA-256 hashes

**Context.** Callers need identity so that jobs have owners and usage can be limited.

**Decision.** Random 256-bit keys, shown once, stored only as SHA-256 hashes; revocable.

**Alternatives.** JWTs from an identity provider; HTTP Basic with passwords.

**Consequences.** Simple and safe for service-to-service use. A fast hash is correct because
the key is already random and long (slow hashes such as bcrypt are for human-chosen
passwords). Cost: one indexed lookup per request, and no user accounts or single sign-on; the
upgrade path is OAuth 2 / OIDC.

---

## 007. A job owned by someone else answers 404, not 403

**Context.** Job ids are random UUIDs, but a 403 would still confirm that an id exists.

**Decision.** Ownership is part of the query (`WHERE id = ? AND user_id = ?`); a miss is a 404.

**Consequences.** No existence leak and no separate "is it yours" step that could be skipped.

---

## 008. The per-user job limit is soft

**Context.** One user should not be able to consume the whole provider quota.

**Decision.** Count the user's active jobs before accepting a new one.

**Alternatives.** A database constraint or advisory lock for a hard limit.

**Consequences.** Two simultaneous submissions can exceed the limit by one. Accepted: the
limit protects capacity, not money or correctness; a hard limit costs a lock on every submit.

---

## 009. Real PostgreSQL in tests (Testcontainers), fake LLM

**Context.** The most important behaviour (claiming, ordering, recovery) lives in SQL.

**Decision.** Integration tests run against a real PostgreSQL 15 in a throwaway container,
with the real Flyway migrations. The LLM gateway is replaced by a scripted fake.

**Alternatives.** H2 in-memory database; mocking the repositories.

**Consequences.** `SKIP LOCKED`, JSONB and constraints behave as in production. Tests are
deterministic and need no API key or quota. Cost: tests need Docker and take longer than unit
tests.

---

## 010. Tasks declare dependencies; independent tasks run in parallel

**Context.** The first version ran a job's tasks strictly one after another, so two
independent research tasks waited for each other and every task received all earlier results.

**Decision.** The planner states, per task, `dependsOn`: the numbers of earlier tasks it needs.
The claim query releases a task only when those tasks are settled. A task receives only its
dependencies' results. If a dependency failed, the task fails at once with `DEPENDENCY_FAILED`
and the model is not called.

**Alternatives.** Keep a strict chain; let the model emit an arbitrary graph and detect cycles.

**Consequences.** A job's duration follows its longest dependency path, not its task count,
and no quota is spent on work that cannot succeed. A task may depend only on EARLIER tasks, so
cycles cannot exist and no cycle detection is needed; the planner validates the rule and asks
the model for one correction. The model decides what is independent, so a wrong judgement
(treating dependent tasks as independent) lowers answer quality but not correctness. Cost: the
planner prompt and schema are now more demanding.
