package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ObservationTruncator;
import com.springagentic.springaiagent.core.domain.Plan;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.core.sandbox.McpContainerFactory;
import com.springagentic.springaiagent.core.sandbox.ManagedSandbox;
import com.springagentic.springaiagent.core.sandbox.SandboxProfile;
import com.springagentic.springaiagent.core.sandbox.ResourceUnavailableException;
import com.springagentic.springaiagent.core.security.SecretRedactor;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
import com.springagentic.springaiagent.framework.config.LlmProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final Planner planner;
    private final Reasoner reasoner;
    private final ToolExecutor toolExecutor;
    private final MemoryStore memoryStore;
    private final ObservationTruncator observationTruncator;
    private final ToolRegistry toolRegistry;
    private final McpContainerFactory containerFactory;
    private final SecretRedactor secretRedactor;
    private final LlmProperties llmProperties;

    public ExecutionEngine(Planner planner, Reasoner reasoner, ToolExecutor toolExecutor, 
                           MemoryStore memoryStore, ObservationTruncator observationTruncator,
                           ToolRegistry toolRegistry, McpContainerFactory containerFactory,
                           SecretRedactor secretRedactor, LlmProperties llmProperties) {
        this.planner = planner;
        this.reasoner = reasoner;
        this.toolExecutor = toolExecutor;
        this.memoryStore = memoryStore;
        this.observationTruncator = observationTruncator;
        this.toolRegistry = toolRegistry;
        this.containerFactory = containerFactory;
        this.secretRedactor = secretRedactor;
        this.llmProperties = llmProperties;
    }

    public AgentContext runComplexTask(String threadId, String userGoal, AgentDefinition agentDef) {
        // 1. Initialize Context & save to persistent memory
        AgentContext context = new AgentContext(threadId, userGoal);
        memoryStore.saveRun(context);
        executeLoop(context, agentDef);
        return context;
    }

    public AgentContext resumeComplexTask(AgentContext context, AgentDefinition agentDef) {
        executeLoop(context, agentDef);
        return context;
    }

    private String executeLoop(AgentContext context, AgentDefinition agentDef) {
        // Fetch thread history
        List<String> history = memoryStore.getThreadHistory(context.getThreadId());

        // 2. Initial Planning Phase if plan is not set
        if (context.getPlan() == null) {
            context.setPlan(planner.createPlan(context, history));
        }

        // 3. The Main Parallel Execution Loop
        while (!context.isTerminated() && !context.getStatus().equals("AWAITING_APPROVAL") && 
               !context.hasExceededLimits(llmProperties.guardrails().maxActions(), 
                                         llmProperties.guardrails().maxReplans(), 
                                         llmProperties.guardrails().maxTokenBudget())) {
            boolean replanTriggered = false;
            Plan currentPlan = context.getPlan();

            // Keep track of remaining steps to run in this plan
            List<Step> remainingSteps = new ArrayList<>(currentPlan.steps());

            // Remove steps that are already completed (already have a summary in context)
            remainingSteps.removeIf(step -> context.getStepSummaries().containsKey(step.stepId()));

            List<Step> stepsToExecute;

            // Check if we are resuming a suspended step
            if (context.getSuspendedStepId() != null) {
                String suspendedId = context.getSuspendedStepId();
                Step suspendedStep = remainingSteps.stream()
                        .filter(s -> s.stepId().equals(suspendedId))
                        .findFirst()
                        .orElse(null);

                if (suspendedStep == null) {
                    log.error("Resumed step '{}' not found in remaining steps: {}", suspendedId, remainingSteps);
                    context.terminate("FATAL_ERROR", "Resumed step " + suspendedId + " not found in plan.");
                    break;
                }
                stepsToExecute = List.of(suspendedStep);
            } else {
                // Identify steps whose dependencies are fully completed
                List<Step> readySteps = new ArrayList<>();
                for (Step step : remainingSteps) {
                    boolean depsSatisfied = true;
                    if (step.dependsOn() != null) {
                        for (String depId : step.dependsOn()) {
                            if (!context.getStepSummaries().containsKey(depId)) {
                                depsSatisfied = false;
                                break;
                            }
                        }
                    }
                    if (depsSatisfied) {
                        readySteps.add(step);
                    }
                }

                if (readySteps.isEmpty()) {
                    if (remainingSteps.isEmpty()) {
                        break;
                    }
                    log.error("DAG SCHEDULER DEADLOCK: Unsatisfied or circular dependencies in steps: {}", remainingSteps);
                    context.terminate("FATAL_ERROR", "Step execution deadlocked on circular or unsatisfied dependencies.");
                    break;
                }
                stepsToExecute = readySteps;
            }

            // Remove scheduled steps from the remaining list
            remainingSteps.removeAll(stepsToExecute);

            log.info("DAG SCHEDULER: Scheduling {} steps in parallel: {}", 
                     stepsToExecute.size(), stepsToExecute.stream().map(Step::stepId).toList());

            // 1. Save single baseline checkpoint before parallel execution fanning
            context.saveCheckpoint();

            BatchTransactionCoordinator coordinator = new BatchTransactionCoordinator(context.getRunId() + "-" + System.currentTimeMillis());

            // Execute ready steps in parallel using virtual threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<StepResult>> futures = new ArrayList<>();
                for (Step step : stepsToExecute) {
                    Future<StepResult> future = executor.submit(() -> executeSingleStep(context, step, agentDef, coordinator));
                    coordinator.registerFuture(future);
                    futures.add(future);
                }

                List<StepResult> stepResults = new ArrayList<>();
                for (var future : futures) {
                    try {
                        stepResults.add(future.get());
                    } catch (Exception e) {
                        log.error("Virtual Thread step execution failed with exception", e);
                        stepResults.add(new StepResult(null, "Execution exception: " + e.getMessage(), true, new ArrayList<>(), 0));
                    }
                }

                boolean rolledBackThisBatch = false;

                // Thread-Safe Convergent Writes to the shared context
                synchronized (context) {
                    for (StepResult res : stepResults) {
                        if (res == null) continue;

                        // Merge observations and action counts
                        for (String obs : res.accumulatedObservations()) {
                            context.addObservationRaw(obs);
                        }
                        context.incrementActionCount(res.actionCount());

                        if ("AWAITING_APPROVAL".equals(res.status())) {
                            log.info("SUSPENDED: Step '{}' is awaiting human approval.", res.stepId());
                            break;
                        } else if (res.replanTriggered()) {
                            replanTriggered = true;
                            log.info("PIVOT: Replanning triggered during step execution.");
                            context.incrementReplanCount();
                            context.setPlan(planner.createPlan(context, history));
                        } else if (res.terminated()) {
                            log.error("Execution terminated due to step error: {}", res.errorMessage());
                            context.terminate("FATAL_ERROR", res.errorMessage());
                        } else if (res.success()) {
                            context.putStepSummary(res.stepId(), res.summary());
                            context.addObservationRaw("Step: " + res.stepId() + " Result: Completed. " + res.summary());
                        } else {
                            // Step failed softly: Rollback and replan (only rollback once per batch)
                            if (!rolledBackThisBatch) {
                                log.warn("Step '{}' failed: {}. Triggering rollback...", res.stepId(), res.errorMessage());
                                boolean rolledBack = context.rollback();
                                rolledBackThisBatch = true;
                                if (!rolledBack) {
                                    log.error("Rollback failed (no checkpoints or mutating boundary crossed). Terminating loop.");
                                    context.terminate("FATAL_ERROR", "Step " + res.stepId() + " failed: " + res.errorMessage());
                                } else {
                                    log.info("Rollback successful. Triggering replanning to recover.");
                                    context.incrementReplanCount();
                                    context.setPlan(planner.createPlan(context, history));
                                    replanTriggered = true;
                                }
                            }
                        }
                    }

                    // Discard baseline checkpoint if we didn't rollback, to prevent memory leaks
                    if (!rolledBackThisBatch) {
                        context.discardCheckpoint();
                    }

                    memoryStore.saveRun(context);
                }

                if (context.isTerminated() || context.getStatus().equals("AWAITING_APPROVAL")) {
                    break;
                }
            }
        } // End Main Loop

        // If we finished all remaining steps in the plan successfully without re-planning
        Plan finalPlan = context.getPlan();
        List<Step> finalRemaining = new ArrayList<>(finalPlan.steps());
        finalRemaining.removeIf(step -> context.getStepSummaries().containsKey(step.stepId()));
        if (!context.isTerminated() && !context.getStatus().equals("AWAITING_APPROVAL") && finalRemaining.isEmpty()) {
            String lastStepId = finalPlan.steps().isEmpty() ? null : finalPlan.steps().get(finalPlan.steps().size() - 1).stepId();
            String finalSummary = lastStepId != null ? context.getStepSummaries().get(lastStepId) : null;
            String conclusion = "All planned steps were completed successfully.";
            if (finalSummary != null && !finalSummary.trim().isEmpty()) {
                conclusion += " Result: " + finalSummary;
            }
            context.terminate("SUCCESS", conclusion);
        }

        // Final Guardrail Check
        if (context.hasExceededLimits(llmProperties.guardrails().maxActions(), 
                                     llmProperties.guardrails().maxReplans(), 
                                     llmProperties.guardrails().maxTokenBudget()) 
            && !context.isTerminated() && !context.getStatus().equals("AWAITING_APPROVAL")) {
            context.terminate("GUARDRAIL_TRIGGERED", "Agent exceeded max actions, replan loops, or token budget.");
        }

        memoryStore.saveRun(context);
        return context.getFinalConclusion();
    }

    private StepResult executeSingleStep(AgentContext sharedContext, Step step, AgentDefinition agentDef, BatchTransactionCoordinator coordinator) {
        if (!coordinator.checkHealth()) {
            return new StepResult(step.stepId(), "Aborted: Sibling step failed.", false, new ArrayList<>(), 0);
        }

        int localActionCount = 0;
        List<String> accumulatedStepObservations = new ArrayList<>();
        List<String> localActionHistory = new ArrayList<>();

        try {
            org.slf4j.MDC.put("threadId", sharedContext.getThreadId());

            // Create an isolated view of the current observations to prevent reading mutating data during a turn
            List<String> localObservations;
            synchronized (sharedContext) {
                localObservations = new ArrayList<>(sharedContext.getObservationsList());
            }

            boolean stepComplete = false;

        // Check if we are resuming a suspended step
        boolean resumingSuspended = false;
        String resumedToolName = null;
        String resumedToolArgs = null;
        String humanDecision = null;
        String humanFeedback = null;
        String modifiedToolArgs = null;

        synchronized (sharedContext) {
            if (step.stepId().equals(sharedContext.getSuspendedStepId())) {
                resumingSuspended = true;
                resumedToolName = sharedContext.getSuspendedToolName();
                resumedToolArgs = sharedContext.getSuspendedToolArgs();
                humanDecision = sharedContext.getHumanDecision();
                humanFeedback = sharedContext.getHumanFeedback();
                modifiedToolArgs = sharedContext.getModifiedToolArgs();
                
                // Clear the suspension state on the context
                sharedContext.clearSuspension();
            }
        }

        if (resumingSuspended) {
            log.info("RESUMING Step [{}]: Tool [{}] Decision [{}]", step.stepId(), resumedToolName, humanDecision);
            if ("APPROVED".equalsIgnoreCase(humanDecision)) {
                String finalArgs = (modifiedToolArgs != null && !modifiedToolArgs.trim().isEmpty()) ? modifiedToolArgs : resumedToolArgs;
                if (toolRegistry.isMutating(resumedToolName)) {
                    log.info("MUTATING ACTION: Clearing memento checkpoints at side-effect boundary.");
                    coordinator.markMutated();
                    sharedContext.clearCheckpoints();
                }
                String obs;
                try {
                    secretRedactor.assertClean(resumedToolName, finalArgs);
                    if (toolExecutor.supports(resumedToolName)) {
                        String toolResult = toolExecutor.execute(resumedToolName, finalArgs);
                        String truncatedResult = observationTruncator.truncate(toolResult, llmProperties.guardrails().maxObservationTokens());
                        obs = "Action [" + resumedToolName + "] Result: " + truncatedResult;
                    } else {
                        SandboxProfile profile = toolRegistry.getSandboxProfile(resumedToolName);
                        try (ManagedSandbox sandbox = containerFactory.lease(profile)) {
                            String toolResult = executeToolInSandbox(resumedToolName, finalArgs, sandbox);
                            String truncatedResult = observationTruncator.truncate(toolResult, llmProperties.guardrails().maxObservationTokens());
                            obs = "Action [" + resumedToolName + "] Result: " + truncatedResult;
                        } catch (ResourceUnavailableException e) {
                            log.error("Sandbox pool exhausted for {}", resumedToolName, e);
                            obs = "Action [" + resumedToolName + "] Result: FAILED — sandbox pool exhausted.";
                        }
                    }
                } catch (SecurityException e) {
                    log.error("DLP BLOCK: Security violation for {}", resumedToolName, e);
                    obs = "Action [" + resumedToolName + "] Result: SECURITY_VIOLATION — execution blocked.";
                } catch (Exception e) {
                    String errorMsg = "FAILED: Tool execution encountered an error: " + e.getMessage();
                    log.error("Tool execution error inside step " + step.stepId(), e);
                    obs = "Action [" + resumedToolName + "] Result: " + errorMsg;
                }
                localObservations.add(obs);
                accumulatedStepObservations.add(obs);
                localActionCount++;
                // Continue to while-loop to let reasoner finalize the step

            } else if ("REJECTED".equalsIgnoreCase(humanDecision)) {
                String obs = "Action [" + resumedToolName + "] Result: REJECTED BY USER" + (humanFeedback != null ? ": " + humanFeedback : "");
                log.info("STEP ABORTED (rejected) [Step {}]: {}", step.stepId(), obs);
                localObservations.add(obs);
                accumulatedStepObservations.add(obs);
                localActionCount++;
                // Continue to while-loop to let reasoner adapt

            } else { // FEEDBACK — human wants the agent to re-reason with their comments
                String obs = "Action [" + resumedToolName + "] Result: SUSPENDED. Human feedback provided: " + (humanFeedback != null ? humanFeedback : "");
                localObservations.add(obs);
                accumulatedStepObservations.add(obs);
                localActionCount++;
                // Fall through into the while-loop so the reasoner can adapt
            }
        }
        while (!stepComplete && !sharedContext.hasExceededLimits(llmProperties.guardrails().maxActions(), 
                                                                llmProperties.guardrails().maxReplans(), 
                                                                llmProperties.guardrails().maxTokenBudget())) {
            if (!coordinator.checkHealth()) {
                return new StepResult(step.stepId(), "Aborted: Sibling step failed.", false, accumulatedStepObservations, localActionCount);
            }

            // Stagnation Check
            boolean stagnated = false;
            int threshold = llmProperties.guardrails().stagnationThreshold();
            if (localActionHistory.size() >= threshold) {
                String last = localActionHistory.get(localActionHistory.size() - 1);
                stagnated = true;
                for (int i = 1; i < threshold; i++) {
                    if (!last.equals(localActionHistory.get(localActionHistory.size() - 1 - i))) {
                        stagnated = false;
                        break;
                    }
                }
            }

            if (stagnated) {
                log.warn("STAGNATION DETECTED [Step {}]: Agent is looping with same tool/args.", step.stepId());
                String obs = "Action [STAGNATION_ERROR] Result: You are repeating yourself. Try a different approach or tool.";
                localObservations.add(obs);
                accumulatedStepObservations.add(obs);
                localActionCount++;
                // Force a replan by returning a replan result
                return new StepResult(step.stepId(), accumulatedStepObservations, localActionCount, true);
            }
            // Build local transient context for Reasoner reasoning
            AgentContext localContext = new AgentContext(sharedContext.getThreadId(), sharedContext.getUserGoal());
            localContext.setPlan(sharedContext.getPlan());
            for (String obs : localObservations) {
                localContext.addObservationRaw(obs);
            }

            ReasoningResult decision = reasoner.think(localContext, step, agentDef.allowedToolNames());

            switch (decision) {
                case ReasoningResult.Action action -> {
                    log.info("ACTION [Step {}]: Executing tool '{}'", step.stepId(), action.toolName());
                    localActionHistory.add(Integer.toHexString((action.toolName() + action.jsonArgs()).hashCode()));

                    if (!agentDef.allowedToolNames().contains(action.toolName())) {
                        String errorMsg = "FAILED: Tool '" + action.toolName() + "' is not allowed for this agent. Allowed tools are: " + agentDef.allowedToolNames();
                        log.warn("Execution Escape Blocked: {}", errorMsg);
                        String obs = "Action [" + action.toolName() + "] Result: " + errorMsg;
                        localObservations.add(obs);
                        accumulatedStepObservations.add(obs);
                        localActionCount++;
                    } else if (toolRegistry.requiresApproval(action.toolName())) {
                        log.info("APPROVAL REQUIRED: Suspending execution for tool '{}'", action.toolName());
                        synchronized (sharedContext) {
                            sharedContext.suspend(step.stepId(), action.toolName(), action.jsonArgs());
                        }
                        return StepResult.suspend(step.stepId(), accumulatedStepObservations, localActionCount);
                    } else {
                        if (!coordinator.checkHealth()) {
                            return new StepResult(step.stepId(), "Aborted: Sibling step failed.", false, accumulatedStepObservations, localActionCount);
                        }
                        // Mutating Checkpoint boundary management
                        if (toolRegistry.isMutating(action.toolName())) {
                            log.info("MUTATING ACTION: Clearing memento checkpoints at side-effect boundary.");
                            coordinator.markMutated();
                            sharedContext.clearCheckpoints();
                        }

                        String obs;
                        try {
                            // 1. Scrub arguments through DLP before any dispatch
                            secretRedactor.assertClean(action.toolName(), action.jsonArgs());

                            ReasoningTraceLogger.logTrace(sharedContext.getThreadId(), step.stepId(), "TOOL_EXECUTION_START", 
                                String.format("Tool: %s\nArgs: %s", action.toolName(), action.jsonArgs()));

                            if (toolExecutor.supports(action.toolName())) {
                                String toolResult = toolExecutor.execute(action.toolName(), action.jsonArgs());
                                ReasoningTraceLogger.logTrace(sharedContext.getThreadId(), step.stepId(), "TOOL_EXECUTION_RESULT", toolResult);
                                String truncatedResult = observationTruncator.truncate(toolResult, llmProperties.guardrails().maxObservationTokens());
                                obs = "Action [" + action.toolName() + "] Result: " + truncatedResult;
                            } else {
                                // Run inside leased sandbox
                                SandboxProfile profile = toolRegistry.getSandboxProfile(action.toolName());
                                try (ManagedSandbox sandbox = containerFactory.lease(profile)) {
                                    String toolResult = executeToolInSandbox(action.toolName(), action.jsonArgs(), sandbox);
                                    ReasoningTraceLogger.logTrace(sharedContext.getThreadId(), step.stepId(), "TOOL_EXECUTION_RESULT_SANDBOX", toolResult);
                                    String truncatedResult = observationTruncator.truncate(toolResult, llmProperties.guardrails().maxObservationTokens());
                                    obs = "Action [" + action.toolName() + "] Result: " + truncatedResult;
                                } catch (ResourceUnavailableException e) {
                                    log.error("Sandbox pool exhausted for {}", action.toolName(), e);
                                    obs = "Action [" + action.toolName() + "] Result: FAILED — sandbox pool exhausted.";
                                }
                            }
                        } catch (SecurityException e) {
                            log.error("DLP BLOCK: Security violation for {}", action.toolName(), e);
                            obs = "Action [" + action.toolName() + "] Result: SECURITY_VIOLATION — execution blocked.";
                        } catch (Exception e) {
                            String errorMsg = "FAILED: Tool execution encountered an error: " + e.getMessage();
                            log.error("Tool execution error inside step " + step.stepId(), e);
                            obs = "Action [" + action.toolName() + "] Result: " + errorMsg;
                        }
                        localObservations.add(obs);
                        accumulatedStepObservations.add(obs);
                        localActionCount++;
                    }
                }

                case ReasoningResult.FinalAnswer answer -> {
                    log.info("STEP COMPLETE [Step {}]: {}", step.stepId(), answer.text());
                    return new StepResult(step.stepId(), answer.text(), accumulatedStepObservations, localActionCount);
                }

                case ReasoningResult.Replan replan -> {
                    log.info("PIVOT [Step {}]: Replanning requested: {}", step.stepId(), replan.reason());
                    return new StepResult(step.stepId(), accumulatedStepObservations, localActionCount, true);
                }

                case ReasoningResult.Error error -> {
                    log.error("ERROR [Step {}]: {}", step.stepId(), error.errorMessage());
                    return new StepResult(step.stepId(), error.errorMessage(), false, accumulatedStepObservations, localActionCount);
                }
            }
        }

            return new StepResult(step.stepId(), "Step action count limit exceeded.", false, accumulatedStepObservations, localActionCount);
        } catch (Exception e) {
            log.error("Exception in step run, invalidating batch", e);
            coordinator.invalidateBatch(); // Trigger poison pill cancellation
            return new StepResult(step.stepId(), "Exception: " + e.getMessage(), true, accumulatedStepObservations, localActionCount);
        } finally {
            org.slf4j.MDC.remove("threadId");
        }
    }

    private String executeToolInSandbox(String toolName, String jsonArgs, ManagedSandbox sandbox) throws Exception {
        String argsToUse = (jsonArgs == null || jsonArgs.isBlank()) ? "{}" : jsonArgs;
        if ("execute_python_sandbox".equals(toolName)) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(argsToUse);
                String code = node.has("code") ? node.get("code").asText() : "";
                String escapedCode = code.replace("'", "'\\''");
                return sandbox.executeCommand("python3 -c '" + escapedCode + "'", Duration.ofSeconds(30));
            } catch (Exception e) {
                return "Error parsing python code parameters: " + e.getMessage();
            }
        } else if ("network_fetch_proxy".equals(toolName)) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(argsToUse);
                String url = node.has("url") ? node.get("url").asText() : "";
                if (url.contains("'") || url.contains(";") || url.contains("&") || url.contains("|")) {
                    throw new IllegalArgumentException("Invalid URL characters detected.");
                }
                return sandbox.executeCommand("curl -sSL '" + url + "'", Duration.ofSeconds(30));
            } catch (Exception e) {
                return "Error executing network fetch: " + e.getMessage();
            }
        } else if ("query_isolated_database".equals(toolName)) {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(argsToUse);
                String query = node.has("query") ? node.get("query").asText() : "";
                String escapedQuery = query.replace("'", "'\\''");
                return sandbox.executeCommand("psql -c '" + escapedQuery + "'", Duration.ofSeconds(30));
            } catch (Exception e) {
                return "Error executing database query: " + e.getMessage();
            }
        } else {
            return sandbox.executeCommand("echo 'Executing unknown tool: " + toolName + " with args: " + argsToUse + "'", Duration.ofSeconds(5));
        }
    }

    // Step Execution return record
    private record StepResult(
        String stepId,
        String summary,
        boolean success,
        String errorMessage,
        List<String> accumulatedObservations,
        int actionCount,
        boolean replanTriggered,
        boolean terminated,
        String status
    ) {
        // Success Constructor
        public StepResult(String stepId, String summary, List<String> accumulatedObservations, int actionCount) {
            this(stepId, summary, true, null, accumulatedObservations, actionCount, false, false, "SUCCESS");
        }

        // Replan Constructor
        public StepResult(String stepId, List<String> accumulatedObservations, int actionCount, boolean replanTriggered) {
            this(stepId, null, false, null, accumulatedObservations, actionCount, replanTriggered, false, "REPLAN");
        }

        // Failure/Exception Constructor
        public StepResult(String stepId, String errorMessage, boolean terminated, List<String> accumulatedObservations, int actionCount) {
            this(stepId, null, false, errorMessage, accumulatedObservations, actionCount, false, terminated, "FAILED");
        }

        // Suspension Constructor
        public static StepResult suspend(String stepId, List<String> accumulatedObservations, int actionCount) {
            return new StepResult(stepId, null, false, null, accumulatedObservations, actionCount, false, false, "AWAITING_APPROVAL");
        }
    }
}