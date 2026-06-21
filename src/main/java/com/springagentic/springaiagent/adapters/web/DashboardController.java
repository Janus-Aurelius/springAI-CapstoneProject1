package com.springagentic.springaiagent.adapters.web;

import com.springagentic.springaiagent.adapters.memory.AgentEvaluationEntity;
import com.springagentic.springaiagent.adapters.memory.AgentEvaluationRepository;
import com.springagentic.springaiagent.adapters.memory.AgentRunEntity;
import com.springagentic.springaiagent.adapters.memory.AgentRunRepository;
import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.engine.ExecutionEngine;
import com.springagentic.springaiagent.core.engine.TaskRouter;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final TaskRouter taskRouter;
    private final ExecutionEngine executionEngine;
    private final MemoryStore memoryStore;
    private final AgentRunRepository agentRunRepository;
    private final AgentEvaluationRepository agentEvaluationRepository;
    private final ApplicationContext applicationContext;

    public DashboardController(TaskRouter taskRouter,
                               ExecutionEngine executionEngine,
                               MemoryStore memoryStore,
                               AgentRunRepository agentRunRepository,
                               AgentEvaluationRepository agentEvaluationRepository,
                               ApplicationContext applicationContext) {
        this.taskRouter = taskRouter;
        this.executionEngine = executionEngine;
        this.memoryStore = memoryStore;
        this.agentRunRepository = agentRunRepository;
        this.agentEvaluationRepository = agentEvaluationRepository;
        this.applicationContext = applicationContext;
    }

    private List<String> getAllAllowedTools() {
        List<String> allowedTools = new ArrayList<>(List.of(
                "search_database", "calculate_metrics", "write_database", "search_archive", "web_search",
                "save_memory", "search_memory",
                "execute_python_sandbox", "network_fetch_proxy", "query_isolated_database"
        ));
        Map<String, McpSyncClient> clientsMap = applicationContext.getBeansOfType(McpSyncClient.class);
        for (McpSyncClient client : clientsMap.values()) {
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

    private AgentDefinition getDummyAgent() {
        return new AgentDefinition(
                "generic-assistant-01",
                "You are a helpful assistant.",
                getAllAllowedTools(),
                0.7
        );
    }

    @GetMapping
    public String getDashboard(Model model) {
        List<AgentRunEntity> runs = agentRunRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
        model.addAttribute("runs", runs);

        Map<String, AgentEvaluationEntity> evaluations = new HashMap<>();
        for (AgentRunEntity run : runs) {
            agentEvaluationRepository.findFirstByRunIdOrderByCreatedAtDesc(run.getRunId())
                    .ifPresent(eval -> evaluations.put(run.getRunId(), eval));
        }
        model.addAttribute("evaluations", evaluations);

        return "dashboard";
    }

    @PostMapping("/chat")
    public String startChat(@RequestParam("prompt") String prompt,
                            @RequestParam(value = "threadId", required = false) String threadIdOpt,
                            Model model) {
        String threadId = (threadIdOpt != null && !threadIdOpt.trim().isEmpty()) ? threadIdOpt.trim() : UUID.randomUUID().toString();

        log.info("Dashboard: Starting asynchronous task for Thread: {}", threadId);

        // Run the agent in a background thread — the engine will create and persist its own AgentContext
        CompletableFuture.runAsync(io.opentelemetry.context.Context.current().wrap(() -> {
            try {
                taskRouter.routeAndExecute(threadId, prompt, getDummyAgent());
            } catch (Exception e) {
                log.error("Dashboard: Error running agent task", e);
            }
        }));

        // Return a pending placeholder card that self-polls until the real run appears in the DB
        model.addAttribute("threadId", threadId);
        model.addAttribute("prompt", prompt);
        return "fragments :: pending-task-row";
    }

    @GetMapping("/tasks/pending/{threadId}")
    public String getPendingTaskRow(@PathVariable("threadId") String threadId,
                                    @RequestParam("prompt") String prompt,
                                    Model model,
                                    jakarta.servlet.http.HttpServletResponse response) {
        // Look for the latest real run for this thread
        List<AgentRunEntity> runs = agentRunRepository.findByThreadId(threadId);
        if (!runs.isEmpty()) {
            // Return the real task-row, replacing the pending placeholder
            AgentRunEntity latestRun = runs.get(runs.size() - 1);
            model.addAttribute("run", latestRun);
            agentEvaluationRepository.findFirstByRunIdOrderByCreatedAtDesc(latestRun.getRunId())
                    .ifPresent(eval -> model.addAttribute("eval", eval));
            return "fragments :: task-row";
        }
        // No real run yet — re-render the pending placeholder (keeps polling)
        model.addAttribute("threadId", threadId);
        model.addAttribute("prompt", prompt);
        return "fragments :: pending-task-row";
    }

    @GetMapping("/tasks/{runId}/row")
    public String getTaskRow(@PathVariable("runId") String runId, Model model, jakarta.servlet.http.HttpServletResponse response) {
        AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            response.setStatus(404);
            return null;
        }
        model.addAttribute("run", run);
        agentEvaluationRepository.findFirstByRunIdOrderByCreatedAtDesc(runId)
                .ifPresent(eval -> model.addAttribute("eval", eval));
        return "fragments :: task-row";
    }

    @GetMapping("/tasks/{runId}")
    public String getTaskDetails(@PathVariable("runId") String runId, Model model, jakarta.servlet.http.HttpServletResponse response) {
        AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            response.setStatus(404);
            return null;
        }
        model.addAttribute("run", run);

        Optional<AgentContext> contextOpt = memoryStore.loadRun(run.getThreadId(), run.getRunId());
        if (contextOpt.isPresent()) {
            model.addAttribute("context", contextOpt.get());
        }

        Optional<AgentEvaluationEntity> evalOpt = agentEvaluationRepository.findFirstByRunIdOrderByCreatedAtDesc(runId);
        if (evalOpt.isPresent()) {
            model.addAttribute("evaluation", evalOpt.get());
        }
        return "fragments :: task-details";
    }

    @PostMapping("/resume")
    public String resumeTask(@RequestParam("threadId") String threadId,
                             @RequestParam("runId") String runId,
                             @RequestParam("decision") String decision,
                             @RequestParam(value = "feedback", required = false) String feedback,
                             @RequestParam(value = "modifiedToolArgs", required = false) String modifiedToolArgs,
                             Model model) {
        log.info("Dashboard: Resuming run {} with decision {}", runId, decision);

        boolean claimed = memoryStore.claimSuspendedRun(threadId, runId);
        if (!claimed) {
            throw new IllegalStateException("Run is not in AWAITING_APPROVAL status or does not exist.");
        }

        AgentContext context = memoryStore.loadRun(threadId, runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found."));

        context.resume(decision.toUpperCase(), feedback, modifiedToolArgs);
        memoryStore.saveRun(context);

        // Resume in background thread
        CompletableFuture.runAsync(io.opentelemetry.context.Context.current().wrap(() -> {
            try {
                executionEngine.resumeComplexTask(context, getDummyAgent());
            } catch (Exception e) {
                log.error("Dashboard: Error resuming task", e);
            }
        }));

        AgentRunEntity run = agentRunRepository.findById(runId).orElse(null);
        model.addAttribute("run", run);
        model.addAttribute("context", context);

        return "fragments :: task-details";
    }
}
