# Spring Boot AI Agent — Comprehensive Architectural & Code-Level Audit Report

This document presents a comprehensive architectural and code-level audit of the Spring Boot AI Agent application. The primary purpose of this application is **to act as an autonomous agent that plans and executes complex workflows by decomposing tasks into a Directed Acyclic Graph (DAG), executing tools dynamically via Model Context Protocol (MCP) clients, and operating safely inside isolated Docker-managed sandbox environments.**

---

## 1. AI Agent Architecture & Features

### Current State Assessment
*   **Custom ReAct / Orchestration Loop:** The application contains a custom orchestration engine (`ExecutionEngine`) coordinating a planning phase (`Planner`) and a reasoning/tool execution phase (`Reasoner`). This custom orchestrator executes ready tasks in parallel via Java 21 virtual threads.
*   **Low Framework Utilization:** While the project imports Spring AI, it only uses `OpenAiChatModel` for basic completions. It does not utilize Spring AI's higher-level abstractions like `ChatClient`, `Advisors`, or built-in structured output converters. Instead, it relies on manual JSON regex extraction and parsing in `cleanAndExtractJson`.
*   **Disconnected memory beans:** The `RedisCloudAgentChatMemory` class implements Spring AI's `ChatMemory` and calls an external REST endpoint, but this bean is only injected into `AgentController` for a test endpoint. The core execution engine is completely disconnected from this and uses `InMemoryStore` to load thread histories.
*   **Lack of Native RAG:** The dependency `spring-ai-advisors-vector-store` is loaded, but no vector store configuration (e.g. pgvector, redis vector search) or retrieval strategy is implemented in the application code.
*   **Fragile Mutation Classification:** The `McpToolProvider` heuristically determines if an MCP tool is mutating and requires human-in-the-loop approval by matching keywords (e.g. `write`, `delete`, `update`, `run`) in the tool name or description.

### Actionable Recommendations

#### [HIGH] Implement Database or Redis-Backed Run State Persistence
Replace the volatile [InMemoryStore.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/memory/InMemoryStore.java) with a production-grade database or Redis-backed persistent store. Ephemeral memory is lost on container restart, and fails entirely in multi-instance horizontally scaled deployments.

#### [HIGH] Unify Short-Term and Long-Term Chat Memory
Integrate [RedisCloudAgentChatMemory.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/memory/RedisCloudAgentChatMemory.java) directly into the routing and planning flows. Provide the agent with context from both short-term memory (immediate conversation history) and long-term memory (via the restClient's long-term memory search).

#### [MEDIUM] Integrate Vector Store for Document Search (RAG)
Expose a dedicated vector search tool to the agent. Enable the `pgvector` extension in the existing PostgreSQL container and configure `PgVectorStore` to index internal knowledge and documentation:
```java
@Bean
public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
    return new PgVectorStore(jdbcTemplate, embeddingModel);
}
```

#### [MEDIUM] Tool Metadata Over Heuristic Approvals
Replace the unsafe keyword guessing in [McpToolProvider.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/tools/McpToolProvider.java#L70-L80) with a configuration-driven metadata mapping or a dedicated schema extension where tool permissions are explicitly mapped.

#### [LOW] Supply Tools to the Planner
Provide the list of available tool names and descriptions to the [Planner.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/core/engine/Planner.java) prompt. Currently, the planner creates steps without knowing what tools actually exist, which leads to hallucinated execution plans.

---

## 2. Spring Boot Best Practices & Code Quality

### Current State Assessment
*   **Virtual Threads:** The application correctly enables virtual threads using `spring.threads.virtual.enabled: true` and spawns tasks in parallel via `Executors.newVirtualThreadPerTaskExecutor()`.
*   **Performance Bottlenecks:**
    *   **$O(N^2)$ Message Token Truncation:** In [ContextManager.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/llm/ContextManager.java#L23-L46), the truncation loop repeatedly invokes `countTokens(result)` for the entire list on every removal, causing CPU stagnation during long conversations.
    *   **Blocking Network Requests in supports():** In [McpToolExecutor.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/tools/McpToolExecutor.java#L37-L60), calling `supports()` queries *all* MCP clients over the network via HTTP/SSE `client.listTools()`. If the LLM generates a non-existent tool name, it triggers blocking network hops to every MCP server during every execution iteration.
*   **Severe Sandbox Recovery Bug:** In [McpContainerFactory.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/core/sandbox/McpContainerFactory.java#L214-L232), the error-handling block destroys a failed container first and then immediately calls `inspectContainerCmd(containerId)` on it. This throws a `NotFoundException`, crashing the container replacement logic and leaving the sandbox pool permanently depleted.
*   **Standard Output Pollution:** Configuration details (including API key hints) are written directly to stdout using `System.out.println` instead of the configured SLF4J logger.

### Actionable Recommendations

#### [HIGH] Fix the Container Replacement Bug
In `McpContainerFactory.java`, capture container metadata *before* calling `destroyContainer()`. 
```diff
- destroyContainer(containerId);
- try {
-     var inspect = dockerClient.inspectContainerCmd(containerId).exec();
-     String netMode = inspect.getHostConfig().getNetworkMode();
-     SandboxProfile profile = "none".equals(netMode) ? SandboxProfile.COMPUTE : SandboxProfile.FETCH;
-     String newId = createContainer(profile);
+ try {
+     var inspect = dockerClient.inspectContainerCmd(containerId).exec();
+     String netMode = inspect.getHostConfig().getNetworkMode();
+     SandboxProfile profile = "none".equals(netMode) ? SandboxProfile.COMPUTE : SandboxProfile.FETCH;
+     destroyContainer(containerId);
+     String newId = createContainer(profile);
```

#### [HIGH] Optimize Token Truncation Algorithm
Refactor [ContextManager.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/llm/ContextManager.java) to avoid re-tokenizing the entire list multiple times. Count the list once and decrement the count incrementally:
```java
public List<Message> truncate(List<Message> messages, int maxTokens) {
    int budget = (int) (maxTokens * 0.9);
    List<Message> result = new ArrayList<>(messages);
    int totalTokens = countTokens(result);
    if (totalTokens <= budget) return messages;

    int keepLastN = Math.max(0, Math.min(2, result.size() - 1));
    while (result.size() > (1 + keepLastN) && totalTokens > budget) {
        Message removed = result.remove(1);
        totalTokens -= (countTokens(removed.getText()) + 4); // Remove message tokens + overhead
    }
    return result;
}
```

#### [MEDIUM] Eliminate Network Hops in Tool Check
Modify [McpToolExecutor.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/tools/McpToolExecutor.java) to rely strictly on the local `toolToClientMap` populated at startup. Do not poll remote servers dynamically inside `supports()`:
```java
@Override
public boolean supports(String toolName) {
    return toolToClientMap.containsKey(toolName);
}
```

#### [MEDIUM] Clean Up Logging Best Practices
Replace all instances of `System.out.println` and `System.err.println` (e.g., in `LlmProviderRegistry`, `TaskRouter`, and `ReasoningTraceLogger`) with proper SLF4J log placeholders (e.g., `log.info(...)`, `log.error(...)`).

---

## 3. Observability & Telemetry

### Current State Assessment
*   **Custom Ephemeral Reasoning Logger:** The `ReasoningTraceLogger` writes raw reasoning details directly to a local file `app_reasoning.log` using synchronized blocking file writes. This bypasses Spring's logging framework, blocks threads, and fails in containerized clouds where local file systems are ephemeral.
*   **No LLM Metric Instrumentations:** While `spring-boot-starter-opentelemetry` is present, there are no custom Micrometer counters or gauges configured to track token budgets, LLM latency, streaming Time-To-First-Token (TTFT), error rates, or sandbox pool stats.
*   **No Actuator Extensions:** There are no custom health indicators or actuator endpoints configured to display agent health, active runs, or pool usage metrics.

### Actionable Recommendations

#### [HIGH] Replace Custom File Logger with Logback Appender
Remove `ReasoningTraceLogger.java` and route reasoning traces through SLF4J/Logback using a dedicated marker or sub-logger. Configure a non-blocking `AsyncAppender` in `logback-spring.xml` to output to the `app_reasoning.log` file, ensuring container compatibility and preventing thread lockups.

#### [MEDIUM] Track Token Consumption and Latencies with Micrometer
Inject a `MeterRegistry` into `ResilientLlmRouter` and `SpringAiLlmProvider` to publish AI metrics:
```java
// Example token counter
meterRegistry.counter("llm.tokens.consumed", "provider", providerId, "task", taskType).increment(tokens);

// Example TTFT gauge / timer
Timer.builder("llm.ttft")
     .tag("provider", providerId)
     .register(meterRegistry)
     .record(Duration.between(startTime, firstTokenTime));
```

#### [MEDIUM] Expose Custom Spring Boot Actuator Endpoints
Create custom Actuator endpoints (e.g. `/actuator/agent/sandboxes` or `/actuator/agent/metrics`) to monitor active container leases, cooldown statuses of providers, and running task stats:
```java
@Endpoint(id = "agentSandboxes")
@Component
public class AgentSandboxEndpoint {
    private final McpContainerFactory factory;
    // ... constructor
    @ReadOperation
    public Map<String, Object> getSandboxStats() {
        return Map.of(
            "leasedCompute", factory.getLeasedComputeCount(),
            "leasedFetch", factory.getLeasedFetchCount(),
            "computeQueueSize", factory.getComputeQueueSize(),
            "fetchQueueSize", factory.getFetchQueueSize()
        );
    }
}
```

---

## 4. Infrastructure, Resiliency, & Deployment

### Current State Assessment
*   **Custom Routing & Failover:** The `ResilientLlmRouter` implements a manual multi-provider failover strategy with a custom cooldown tracking map.
*   **Synchronous Execution Flow:** The `/api/v1/agent/chat` endpoint executes tasks synchronously. A complex multi-step ReAct workflow easily takes 30s-120s, leading to client-side timeouts.
*   **No Caching:** No caching layers exist for exact-match prompts or semantic caching, forcing all requests to execute on the external LLMs.

### Actionable Recommendations

#### [HIGH] Transition to an Asynchronous Architecture
Migrate the execution engine to an asynchronous worker model. The `/api/v1/agent/chat` endpoint should validate the request, persist a new `AgentContext` run in status `RUNNING`, dispatch the work to a background executor (using an async queue like RabbitMQ or standard `TaskExecutor`), and immediately return a HTTP 202 containing the `runId`. Clients can poll the status or listen to a WebSocket/Server-Sent Events (SSE) stream.

#### [MEDIUM] Implement Resilience4j for Circuit Breaker & Retry
Remove the manual cooldown loops in [ResilientLlmRouter.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/llm/ResilientLlmRouter.java) and implement Resilience4j's official Spring Boot Starter. Configure circuit breakers and exponential backoff retries for LLM clients to handle HTTP 429 and 5xx errors:
```yaml
resilience4j.retry:
  instances:
    llmProvider:
      maxAttempts: 3
      waitDuration: 2s
      enableExponentialBackoff: true
      exponentialBackoffMultiplier: 2
      retryExceptions:
        - org.springframework.web.client.HttpServerErrorException
        - org.springframework.web.client.HttpClientErrorException.TooManyRequests
```

#### [MEDIUM] Setup Semantic Caching
Use Redis to cache previous agent responses. Implement exact-match caching first, and then introduce semantic caching (by calculating input embeddings and performing a vector similarity search on previous prompts before sending them to the LLM) to minimize cost and latency.

---

## 5. Security

### Current State Assessment
*   **Command Injection Vulnerabilities:** The [DockerManagedSandbox.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/sandbox/DockerManagedSandbox.java#L37-L41) executes shell commands using `sh -c` with string interpolated commands. In [ExecutionEngine.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/core/engine/ExecutionEngine.java#L486-L519), variables are interpolated directly:
    *   `executeToolInSandbox` runs `python3 -c '<escaped_code>'` where single quotes are replaced. If a user bypasses single quotes or forces shell evaluation, they can run arbitrary shell commands on the docker container.
    *   `network_fetch_proxy` executes `curl -sSL '<url>'`. By supplying a URL starting with a hyphen (e.g. `--output /etc/passwd`), an attacker can perform command/argument injection.
*   **Incomplete Secret Scrubbing:** The [DefaultSecretRedactor.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/security/DefaultSecretRedactor.java) only sanitizes the main OpenAI API key (`spring.ai.openai.api-key`). It does not load or check any of the dynamic keys of configured providers (`GEMINI_PROJECT_A_API_KEY`, `GEMINI_PROJECT_B_API_KEY`, etc.), allowing secondary keys to be leaked to external logs or sandboxes.
*   **No LLM Input/Output Guardrails:** The system accepts any prompt input without injection checks, and parses outputs directly without verifying semantic boundaries (e.g. scanning for jailbreaks, system prompt extractions, or harmful actions).

### Actionable Recommendations

#### [HIGH] Remediate Command Injection in Sandboxes
Do not execute strings via shell interpolation (`sh -c`). Execute binaries directly with list parameters to bypass the shell command parsing:
```java
// Example for python sandbox:
ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
        .withCmd("python3", "-c", pythonCode) // Command runs directly as list arguments
        .withAttachStdout(true)
        .withAttachStderr(true)
        .exec();
```
For `network_fetch_proxy`, parse the URL, check that the scheme is strictly `http` or `https`, ensure the host does not resolve to local addresses (SSRF protection), and ensure the input string does not start with `-`.

#### [HIGH] Update SecretRedactor to Track All Multi-Provider Keys
Modify `DefaultSecretRedactor.java` to scan `LlmProperties` and register every provider API key in its list of secrets to prevent secondary credential leakages.
```java
public DefaultSecretRedactor(LlmProperties properties) {
    if (properties.providers() != null) {
        for (var provider : properties.providers()) {
            addSecret(provider.apiKey());
        }
    }
}
```

#### [MEDIUM] Implement Input/Output Guardrails
Introduce prompt injection and output validation filters at the TaskRouter boundary (e.g., integrating an advisor/filter or utilizing models like Llama Guard to classify incoming queries and outgoing responses).

---

## Prioritized Audit Action Checklist

| Priority | Category | Finding / Vulnerability | Target Files |
| :--- | :--- | :--- | :--- |
| **CRITICAL** | Security | Sandbox Command/Argument Injection | [ExecutionEngine.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/core/engine/ExecutionEngine.java) |
| **HIGH** | Security | Leakage of secondary LLM keys due to incomplete redactor | [DefaultSecretRedactor.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/security/DefaultSecretRedactor.java) |
| **HIGH** | Spring Boot | Sandbox recovery bug crashes replacement pool | [McpContainerFactory.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/core/sandbox/McpContainerFactory.java) |
| **HIGH** | Infrastructure | Ephemeral run memory store resets on restart | [InMemoryStore.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/memory/InMemoryStore.java) |
| **HIGH** | Infrastructure | Synchronous API requests lead to timeouts | [AgentController.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/web/AgentController.java) |
| **HIGH** | Quality | Quadratic $O(N^2)$ CPU execution in truncation | [ContextManager.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/llm/ContextManager.java) |
| **MEDIUM** | Quality | Blocking HTTP network calls in supports() checks | [McpToolExecutor.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/tools/McpToolExecutor.java) |
| **MEDIUM** | Observability | Reasoning logs bypass standard framework | [ReasoningTraceLogger.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/core/engine/ReasoningTraceLogger.java) |
| **MEDIUM** | Resilience | Manual retry loops instead of standard library | [ResilientLlmRouter.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/adapters/llm/ResilientLlmRouter.java) |
| **LOW** | Features | Missing Document Search capabilities (RAG) | N/A |
