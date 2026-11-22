# Runbook

For whoever is on call. Commands assume the local Docker database named `pg`; on a real
deployment use `psql` against your database. `$ADMIN_TOKEN` is the admin secret.

## First look

```bash
# Is the application up, and what is the queue doing? (details need the admin token)
curl -s -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/actuator/health

# Jobs by status
docker exec -i pg psql -U orchestrator -d orchestratordb -c \
  "SELECT status, count(*) FROM jobs GROUP BY status ORDER BY 1;"

# Queue by status and retry count
docker exec -i pg psql -U orchestrator -d orchestratordb -c \
  "SELECT status, retry_count, count(*) FROM task_queue GROUP BY 1,2 ORDER BY 1,2;"
```

In the health output: `llmCircuit` is CLOSED (normal), OPEN (provider failing) or HALF_OPEN
(testing). `droppedEvents` above 0 means audit events were lost under load.

## Symptom: jobs are not finishing

| Check | Meaning | Action |
|---|---|---|
| `llmCircuit` is OPEN | The model provider is failing or out of quota | Wait; tasks resume by themselves after the cooldown. Check the provider's quota page. Nothing is lost |
| Many tasks `PENDING`, none `PROCESSING` | Poller stopped (circuit open) or no worker slot | Check circuit; check the log for `Polling failed` |
| Tasks `PROCESSING` for more than 2 minutes with no log activity | Worker died | The rescuer re-queues them within about 30 seconds; look for `Rescued stuck task` |
| Job `EXECUTING`, all tasks settled | Aggregation did not trigger | Recovery picks it up within 10 minutes; look for `Recovering stalled job` |
| Job `FAILED` with "circuit breaker is open" | Submitted during an outage | Ask the user to resubmit |

## Symptom: HTTP 429 for a user

The user has reached the limit of active jobs. List them and look for stuck ones:

```bash
docker exec -i pg psql -U orchestrator -d orchestratordb -c \
  "SELECT id, status, created_at, updated_at FROM jobs WHERE user_id = 'alice'
   AND status NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','FAILED') ORDER BY created_at;"
```

## Symptom: database connections exhausted

Look in the log for `Apparent connection leak detected`; the stack trace names the code that
held a connection longer than 20 s. Check:

```bash
docker exec -i pg psql -U orchestrator -d orchestratordb -c \
  "SELECT state, count(*) FROM pg_stat_activity WHERE datname='orchestratordb' GROUP BY state;"
```

`idle in transaction` should never appear; if it does, a code path holds a transaction during
slow work (see DECISIONS 003).

## Cost and usage

```bash
# Tokens per job, most expensive first
docker exec -i pg psql -U orchestrator -d orchestratordb -c \
  "SELECT job_id, count(*) AS calls, sum(tokens_used) AS tokens, round(avg(latency_ms)) AS avg_ms
     FROM llm_call_logs WHERE job_id IS NOT NULL GROUP BY job_id ORDER BY tokens DESC NULLS LAST LIMIT 10;"

# Failed model calls in the last hour
docker exec -i pg psql -U orchestrator -d orchestratordb -c \
  "SELECT calling_component, count(*) FROM llm_call_logs
    WHERE status = 'FAILED' AND created_at > now() - interval '1 hour' GROUP BY 1;"
```

## Keys

```bash
# Revoke a key (the key id was returned when the key was created)
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8080/api/v1/admin/keys/<keyId> \
  -H "X-Admin-Token: $ADMIN_TOKEN"        # 204 = revoked, 404 = unknown or already revoked
```

A lost key cannot be recovered (only its hash is stored): revoke it and create a new one.

## Following one job

Every log line of a job carries `[job=<id>]`:

```bash
grep "job=<id>" application.log
curl -s -H "X-API-Key: $KEY" localhost:8080/api/v1/jobs/<id>/trace   # every task and agent step
```

## Planned alerts (not yet automated)

| Signal | Threshold idea |
|---|---|
| Circuit OPEN | longer than 5 minutes |
| Oldest PENDING task age | above 10 minutes while circuit is CLOSED |
| Jobs in EXECUTING | older than 30 minutes |
| `droppedEvents` | increasing |
