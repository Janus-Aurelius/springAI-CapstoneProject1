# Spring AI Agent — Team Guide

A practical guide for teammates to start the system, understand how it works, and run both simple and complex (ReAct loop) scenarios.

---

## 1. Prerequisites

| Requirement | Version |
|---|---|
| Docker + Docker Compose | v24+ |
| Java (for local dev only) | 21 |
| `curl` or Postman | any |

### Environment setup

Copy `.env.example` to `.env` and configure your **LLM Fleet**:

```bash
cp ..env.example ..env
```

The system supports a **Resilient Multi-Provider Gateway**. You can configure a primary provider (e.g., Groq for speed) and a fallback (e.g., OpenAI for reliability):

Edit `.env`:
```env
# Primary (e.g. Groq)
OPENAI_API_KEY=gsk_your_groq_key_here
OPENAI_BASE_URL=https://api.groq.com/openai/v1
PLANNING_MODEL=llama-3.1-70b-versatile
REASONING_MODEL=llama-3.1-8b-instant

# Fallback (e.g. OpenAI)
FALLBACK_OPENAI_API_KEY=sk-proj-your-openai-key-here
```

> **Note**: The system is compatible with any OpenAI-standard endpoint (Groq, DeepSeek, OpenAI, vLLM).

---

## 2. Starting the System

```bash
docker compose up
```

Wait for this line in the logs (takes ~30s):

```
spring-ai-agent | Started SpringaiagentApplication in XX seconds
```

Verify the system is healthy:

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

### Services started

| Service | URL | Purpose |
|---|---|---|
| `spring-ai-agent` | `localhost:8080` | The AI Agent API |
| `postgres` | `localhost:5432` | Persistent storage for runs |
| `redis` | `localhost:6379` | Thread memory / chat history |
| `mcp-postgres` | `localhost:8081` | MCP tool: SQL query interface |
| `squid` | `localhost:3128` | Outbound proxy (sandbox) |

---

## 3. Available Mock Tools

The system ships with these dummy tools for demonstration:

| Tool | Type | Needs Approval? | Behaviour |
|---|---|---|---|
| `search_database` | Read | No | Returns "Bob not found"; returns "Alice found" |
| `search_archive` | Read | No | Returns "Bob found (Dormant, ID:11223)" |
| `web_search` | Read | No | Returns Bob's LinkedIn profile |
| `calculate_metrics` | Read | No | Returns mock metric score |
| `write_database` | **Mutating** | **YES** | Writes data — always requires human approval |

---

## 4. Scenario A — Simple Request (No ReAct Loop)

For short, direct questions the system answers without planning or tools.

### Request

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello! Please reply in exactly 5 words."}'
```

### Expected Response

```json
{
  "threadId": "...",
  "runId": "...",
  "status": "SUCCESS",
  "result": "Hello! I am here always.",
  "suspendedStepId": null,
  "suspendedToolName": null,
  "suspendedToolArgs": null
}
```

### What happens internally

```
ROUTER: Simple/conversational request detected → returns direct LLM answer
        No planning, no tools, no execution loop.
```

---

## 5. Scenario B — Multi-Step ReAct Loop (Read-Only)

The agent plans, executes multiple tools, performs **self-correction/recourse**, and returns a final answer — all without requiring human approval.

### Request

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Search the active database for user Bob. If not found, search the archive database for Bob, then calculate his metrics."}'
```

### Expected Response

```json
{
  "threadId": "...",
  "runId": "...",
  "status": "SUCCESS",
  "result": "All planned steps were completed successfully.",
  "suspendedStepId": null,
  "suspendedToolName": null,
  "suspendedToolArgs": null
}
```

### What to watch in the logs

```bash
docker compose logs -f app | grep -E "PLANNER|Step \[|DAG|REASONER|ACTION|STEP COMPLETE|PIVOT"
```

You will see:

```
PLANNER: Generating execution plan...
PLANNER: Plan generated with 3 step(s):
  Step [1/3] id='search_active'   deps=[]              | Search the active database for user Bob
  Step [2/3] id='search_archive'  deps=[search_active] | If not found, search the archive database
  Step [3/3] id='calculate_metrics' deps=[search_archive] | Calculate Bob's metrics

DAG SCHEDULER: Scheduling 1 steps in parallel: [search_active]

REASONER: Analyzing step -> Search the active database for user Bob
ACTION [Step search_active]: Executing tool 'search_database'
  → Result: User 'Bob' not found in active database.

ACTION [Step search_active]: Executing tool 'search_archive'   ← SELF-CORRECTION (recourse)
  → Result: User 'Bob' found. Status: Dormant. ID: 11223.

STEP COMPLETE [Step search_active]

DAG SCHEDULER: Scheduling 1 steps in parallel: [search_archive]
STEP COMPLETE [Step search_archive]

DAG SCHEDULER: Scheduling 1 steps in parallel: [calculate_metrics]
STEP COMPLETE [Step calculate_metrics]
```

### ReAct loop explained

```
┌─────────────────────────────────────────────────────┐
│                    ReAct Loop                        │
│                                                      │
│  PLAN  ──►  THINK (Reasoner)  ──►  ACT (Tool)       │
│              │                         │             │
│              │◄────── OBSERVE ◄────────┘             │
│              │                                       │
│         (repeat until step complete)                 │
└─────────────────────────────────────────────────────┘
```

- **Planner**: Breaks goal into a DAG of steps
- **Reasoner**: For each step, decides what tool to call (or gives a final answer)
- **Executor**: Runs the tool, returns the result as an observation
- **Recourse**: If a tool fails/returns no results, the Reasoner picks an alternative tool in the same step

---

## 6. Scenario C — Replanning (PIVOT)

When a step cannot proceed (missing parameters, unexpected result), the Reasoner returns a **REPLAN** signal. The Planner regenerates the entire plan with updated context.

### Request

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Search the active database for user Bob. If not found, search the archive database for Bob, then calculate his metrics."}'
```

### What to watch in logs

```
PIVOT [Step calculate_metrics]: Replanning requested: metric type and date range not provided
PIVOT: Replanning triggered during step execution.
PLANNER: Generating execution plan...         ← second plan generated with updated context
PLANNER: Plan generated with 2 step(s):
  Step [1/2] id='summarize_findings' ...
  Step [2/2] id='conclude' ...
```

This shows the system **self-correcting at the plan level** (not just the tool level).

---

## 7. Scenario D — Human-in-the-Loop (Mutating Tool Approval)

When the agent wants to call `write_database` (or any mutating tool), execution is **paused** and you must explicitly approve or reject before it continues.

### Step 1 — Submit the request

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Search the active database for user Bob. If not found, search the web for Bob, then save the found profile information back to the database."}'
```

### Step 2 — Read the suspended response

```json
{
  "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
  "runId":    "1c8eda30-e976-4aab-9b81-5a909f621cd8",
  "status": "AWAITING_APPROVAL",
  "result": null,
  "suspendedStepId":   "saveToDatabase",
  "suspendedToolName": "write_database",
  "suspendedToolArgs": "{\"query\": \"name=Bob,status=Dormant,id=11223\"}"
}
```

> Save the `threadId` and `runId` — you need them to resume.

### Step 3a — Approve (continue execution)

```bash
curl -X POST http://localhost:8080/api/v1/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
    "runId":    "1c8eda30-e976-4aab-9b81-5a909f621cd8",
    "decision": "APPROVED",
    "feedback": "Looks good, proceed",
    "modifiedToolArgs": ""
  }'
```

**Expected result:**
```json
{
  "status": "SUCCESS",
  "result": "All planned steps were completed successfully.",
  "suspendedStepId": null
}
```

### Step 3b — Reject (abort the mutating action, trigger replan)

```bash
curl -X POST http://localhost:8080/api/v1/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
    "runId":    "1c8eda30-e976-4aab-9b81-5a909f621cd8",
    "decision": "REJECTED",
    "feedback": "Do not write to the database",
    "modifiedToolArgs": ""
  }'
```

### Step 3c — Modify args then approve

You can change the tool arguments before approving:

```bash
curl -X POST http://localhost:8080/api/v1/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
    "runId":    "1c8eda30-e976-4aab-9b81-5a909f621cd8",
    "decision": "APPROVED",
    "feedback": "Approved with corrected query",
    "modifiedToolArgs": "{\"query\": \"UPDATE users SET email='\''bob@techcorp.com'\'' WHERE id=11223\"}"
  }'
```

### Step 3d — Provide feedback (re-reason without executing)

```bash
curl -X POST http://localhost:8080/api/v1/agent/resume \
  -H "Content-Type: application/json" \
  -d '{
    "threadId": "f8c61d5b-f5a5-418d-acaa-d80bb86201d2",
    "runId":    "1c8eda30-e976-4aab-9b81-5a909f621cd8",
    "decision": "FEEDBACK",
    "feedback": "Only update the email field, not the status",
    "modifiedToolArgs": ""
  }'
```

The agent will re-run the Reasoner with your feedback as an observation and produce a revised tool call (which will again require approval).

---

## 8. Reading the Logs

```bash
# Stream all key agent events live
docker compose logs -f app | grep -E "PLANNER|Step \[|DAG|REASONER|ACTION|STEP COMPLETE|PIVOT|RESUMING|APPROVAL|SUSPENDED|SUCCESS|FATAL"
```

### Log event reference

| Log Pattern | Meaning |
|---|---|
| `PLANNER: Plan generated with N step(s)` | New plan created — shows each step's ID, deps, description |
| `DAG SCHEDULER: Scheduling N steps in parallel` | Which steps are ready to run now |
| `REASONER: Analyzing step ->` | Reasoner is thinking about a step |
| `ACTION [Step X]: Executing tool 'Y'` | Tool being called |
| `EXECUTE: Running [tool] with args: {...}` | Actual args sent to the tool |
| `STEP COMPLETE [Step X]: ...` | Step finished — summary shown |
| `PIVOT [Step X]: Replanning requested` | Reasoner cannot proceed, triggering full replan |
| `APPROVAL REQUIRED: Suspending execution` | Mutating tool detected — waiting for human |
| `RESUMING Step [X]: Tool [Y] Decision [APPROVED]` | Human approved, tool will execute now |
| `STEP COMPLETE (resumed+approved) [Step X]` | Approved tool ran cleanly, step done |
| `MEMORY: Saved state for Run [...]` | Run state persisted to Redis/Postgres |

---

## 9. System Architecture (Quick Reference)

```
HTTP Request
     │
     ▼
 TaskRouter  ──► Simple? ──► Direct LLM answer
     │
     ▼ Complex
 ExecutionEngine
     │
     ├── Planner (generates DAG of Steps)
     │
     └── DAG Scheduler
           │
           ▼ (parallel virtual threads)
        Reasoner ──► Action ──► ToolExecutor ──► Observation
           │◄──────────────────────────────────────┘
           │
           ├── FinalAnswer ──► StepResult(SUCCESS)
           ├── Replan      ──► Planner regenerates plan
           └── Action on mutating tool ──► SUSPEND → wait for /resume
```

---

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `500 Internal Server Error` on `/chat` | LLM API key wrong or model not found | Check `.env` OPENAI_API_KEY and PLANNING_MODEL |
| Plan is `null` or `PLANNER: Received null or empty plan!` | LLM returned invalid JSON | Try a simpler prompt; check provider rate limits |
| `AWAITING_APPROVAL` never resolves | You need to call `/resume` manually | See Section 7 |
| `DAG SCHEDULER DEADLOCK` | Circular dependencies in plan | Model produced a bad plan; retry or simplify prompt |
| `OTLP Connection refused` warnings | No OpenTelemetry collector running | Safe to ignore — metrics export only, does not affect functionality |
| Container won't start | Port conflict | Run `docker compose down` then `docker compose up` |
