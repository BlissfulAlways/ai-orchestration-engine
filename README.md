# ai-orchestration-engine
A system which takes a task, breaks it into subtasks, routes the subtasks to specialized AI agents, and returns one coherent answer

## Architecture

The system is composed of 17 derived components — 7 processing, 9 storage, 1 observability — each derived from first principles by following data flow through the system.

User Goal → API Gateway → Task Planner → Task Router → Task Queue
                                                            ↓
                                                            Agent Executor (loop)
                                                            ↓
                                                            Result Store
                                                            ↓
                                                            Result Aggregator
                                                            ↓
                                                            Final Answer

## Components

| Component | Responsibility |
|---|---|
| API Gateway | Receives goals, returns job ID, serves results |
| Task Planner | Breaks goal into ordered sub-tasks via LLM |
| Task Router | Assigns each sub-task to correct agent type |
| Task Queue | Durable PostgreSQL-backed queue with atomic pickup |
| Agent Executor | Runs think-act-observe loop per task |
| LLM Gateway | Single entry point for all Gemini API calls with rate limit backoff |
| Result Aggregator | Synthesizes all sub-task results into final answer |
| Observer | Structured event logging across all components |

## Technical Decisions

- **PostgreSQL for everything** — relational data, JSONB columns, durable task queue, event logs. Zero polyglot complexity.
- **Atomic task pickup** — `FOR UPDATE SKIP LOCKED` prevents duplicate execution under concurrent executors.
- **@Transactional on planning and routing** — partial writes on crash leave no orphaned data.
- **Gemini 2.5 Flash** — strongest available free-tier model with retry-after backoff on rate limits.
- **No LangChain** — agent loop, prompt construction, and LLM communication written from scratch to demonstrate genuine understanding.
- **Virtual threads** — async pipeline uses Java 21 virtual threads via `Thread.ofVirtual()`.

## Stack

- Java 21, Spring Boot 3.x
- PostgreSQL 15
- Gemini 2.5 Flash API
- React (frontend — in progress)
- Flyway for schema versioning
- GitHub Actions for CI

## Running Locally

### Prerequisites
- Java 21
- Docker
- Gemini API key from https://aistudio.google.com/apikey

### Start PostgreSQL

```bash
docker run --name orchestrator-db \
  -e POSTGRES_USER=orchestrator \
  -e POSTGRES_PASSWORD=orchestrator \
  -e POSTGRES_DB=orchestratordb \
  -p 5432:5432 \
  -d postgres:15
```

### Set API Key

```bash
export GEMINI_API_KEY=your_key_here
```

### Run Backend

```bash
cd backend/ai-orchestrator
./mvnw spring-boot:run
```

### Submit a Job

```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-001", "goal": "Research AI trends and write a summary"}'
```

### Poll for Result

```bash
curl http://localhost:8080/api/jobs/{jobId}
```

## API

| Method | Endpoint | Description |
|---|---|---|
| POST | /api/jobs | Submit a new goal |
| GET | /api/jobs/{jobId} | Get job status and final answer |

## Project Structure
backend/ai-orchestrator/src/main/java/com/orchestrator/ai_orchestrator/
├── apigateway/        # HTTP entry point, job lifecycle
├── planner/           # LLM-based goal decomposition
├── router/            # Agent type assignment
├── taskqueue/         # Durable task queue
├── executor/          # Agent loop execution
├── aggregator/        # Result synthesis
├── llmgateway/        # Gemini API client
├── resultstore/       # Per-task result storage
├── observer/          # System event logging
└── config/            # Application configuration

## What This Demonstrates

- Systems design from first principles — every component derived by following data flow
- Production patterns — atomic operations, transactions, retry with backoff, crash recovery
- Clean architecture — domain, service, infrastructure separation enforced across all packages
- Judgment over trend-chasing — one database, two libraries, zero unnecessary complexity
EOF
