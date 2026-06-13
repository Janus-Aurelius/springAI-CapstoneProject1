package com.springagentic.springaiagent.adapters.web;

import com.springagentic.springaiagent.core.engine.TaskRouter;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.core.engine.ExecutionEngine;
import io.modelcontextprotocol.client.McpSyncClient;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final TaskRouter taskRouter;
    private final ExecutionEngine executionEngine;
    private final MemoryStore memoryStore;
    private final org.springframework.context.ApplicationContext applicationContext;

    public AgentController(TaskRouter taskRouter, ExecutionEngine executionEngine, MemoryStore memoryStore,
                           org.springframework.context.ApplicationContext applicationContext) {
        this.taskRouter = taskRouter;
        this.executionEngine = executionEngine;
        this.memoryStore = memoryStore;
        this.applicationContext = applicationContext;
    }

    private List<String> getAllAllowedTools() {
        List<String> allowedTools = new java.util.ArrayList<>(List.of("search_database", "calculate_metrics", "write_database", "search_archive", "web_search"));
        java.util.Map<String, McpSyncClient> clientsMap = applicationContext.getBeansOfType(McpSyncClient.class);
        for (io.modelcontextprotocol.client.McpSyncClient client : clientsMap.values()) {
            try {
                var toolsResult = client.listTools();
                if (toolsResult != null && toolsResult.tools() != null) {
                    for (var tool : toolsResult.tools()) {
                        allowedTools.add(tool.name());
                    }
                }
            } catch (Exception e) {
                // Ignore connection issues for offline sidecars
            }
        }
        return allowedTools;
    }

    @PostMapping("/chat")
    public RunResponse chat(@RequestBody ChatRequest request) {

        // Define our Agent (In a real app, this comes from a DB)
        AgentDefinition dummyAgent = new AgentDefinition(
                "generic-assistant-01",
                "You are a helpful assistant.",
                getAllAllowedTools(), // Dynamically allowed tools
                0.7
        );

        // Generate a random threadId for this request
        String threadId = request.threadId() != null ? request.threadId() : UUID.randomUUID().toString();

        System.out.println("STARTING REQUEST for Thread: " + threadId);

        // The magic happens here!
        com.springagentic.springaiagent.core.domain.AgentContext context = 
                taskRouter.routeAndExecute(threadId, request.prompt(), dummyAgent);

        return new RunResponse(
                context.getThreadId(),
                context.getRunId(),
                context.getStatus(),
                context.getFinalConclusion(),
                context.getSuspendedStepId(),
                context.getSuspendedToolName(),
                context.getSuspendedToolArgs()
        );
    }

    @PostMapping("/resume")
    public RunResponse resume(@RequestBody ResumeRequest request) {
        // Validate input decisions to prevent injection / bad state transitions
        if (request.decision() == null || 
            (!request.decision().equalsIgnoreCase("APPROVED") && 
             !request.decision().equalsIgnoreCase("REJECTED") && 
             !request.decision().equalsIgnoreCase("FEEDBACK"))) {
            throw new IllegalArgumentException("Invalid decision. Allowed values are: APPROVED, REJECTED, FEEDBACK");
        }

        // Define our Agent
        AgentDefinition dummyAgent = new AgentDefinition(
                "generic-assistant-01",
                "You are a helpful assistant.",
                getAllAllowedTools(), // Dynamically allowed tools
                0.7
        );

        // Atomically claim the run using CAS to prevent double-resumptions (Double-click problem)
        boolean claimed = memoryStore.claimSuspendedRun(request.threadId(), request.runId());
        if (!claimed) {
            throw new IllegalStateException("Run is not in AWAITING_APPROVAL status or does not exist.");
        }

        // Load the context
        com.springagentic.springaiagent.core.domain.AgentContext context = memoryStore.loadRun(request.threadId(), request.runId())
                .orElseThrow(() -> new IllegalArgumentException("Run not found."));

        // Update the context with user inputs
        context.resume(request.decision().toUpperCase(), request.feedback(), request.modifiedToolArgs());

        // Resume execution
        executionEngine.resumeComplexTask(context, dummyAgent);

        return new RunResponse(
                context.getThreadId(),
                context.getRunId(),
                context.getStatus(),
                context.getFinalConclusion(),
                context.getSuspendedStepId(),
                context.getSuspendedToolName(),
                context.getSuspendedToolArgs()
        );
    }

    // Records for incoming HTTP JSON
    public record ChatRequest(String threadId, String prompt) {}

    public record RunResponse(
        String threadId,
        String runId,
        String status,
        String result,
        String suspendedStepId,
        String suspendedToolName,
        String suspendedToolArgs
    ) {}

    public record ResumeRequest(
        String threadId,
        String runId,
        String decision,
        String feedback,
        String modifiedToolArgs
    ) {}
}
