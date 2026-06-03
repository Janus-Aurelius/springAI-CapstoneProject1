package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ObservationTruncator;
import com.springagentic.springaiagent.core.domain.Plan;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
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

    public ExecutionEngine(Planner planner, Reasoner reasoner, ToolExecutor toolExecutor, 
                           MemoryStore memoryStore, ObservationTruncator observationTruncator,
                           ToolRegistry toolRegistry) {
        this.planner = planner;
        this.reasoner = reasoner;
        this.toolExecutor = toolExecutor;
        this.memoryStore = memoryStore;
        this.observationTruncator = observationTruncator;
        this.toolRegistry = toolRegistry;
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
        while (!context.isTerminated() && !context.getStatus().equals("AWAITING_APPROVAL") && !context.hasExceededLimits()) {
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

            // Execute ready steps in parallel using virtual threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<StepResult>> futures = new ArrayList<>();
                for (Step step : stepsToExecute) {
                    futures.add(executor.submit(() -> executeSingleStep(context, step, agentDef)));
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
                            // Step failed softly: Rollback and replan
                            log.warn("Step '{}' failed: {}. Triggering rollback...", res.stepId(), res.errorMessage());
                            boolean rolledBack = context.rollback();
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
                    memoryStore.saveRun(context);
                }

                if (replanTriggered || context.isTerminated() || context.getStatus().equals("AWAITING_APPROVAL")) {
                    break;
                }
            }
        } // End Main Loop

        // If we finished all remaining steps in the plan successfully without re-planning
        Plan finalPlan = context.getPlan();
        List<Step> finalRemaining = new ArrayList<>(finalPlan.steps());
        finalRemaining.removeIf(step -> context.getStepSummaries().containsKey(step.stepId()));
        if (!context.isTerminated() && !context.getStatus().equals("AWAITING_APPROVAL") && finalRemaining.isEmpty()) {
            context.terminate("SUCCESS", "All planned steps were completed successfully.");
        }

        // Final Guardrail Check
        if (context.hasExceededLimits() && !context.isTerminated() && !context.getStatus().equals("AWAITING_APPROVAL")) {
            context.terminate("GUARDRAIL_TRIGGERED", "Agent exceeded max actions or replan loops.");
        }

        memoryStore.saveRun(context);
        return context.getFinalConclusion();
    }

    private StepResult executeSingleStep(AgentContext sharedContext, Step step, AgentDefinition agentDef) {
        // Create an isolated view of the current observations to prevent reading mutating data during a turn
        List<String> localObservations;
        synchronized (sharedContext) {
            localObservations = new ArrayList<>(sharedContext.getObservationsList());
        }

        // Save rollback checkpoint before executing step
        sharedContext.saveCheckpoint();

        boolean stepComplete = false;
        int localActionCount = 0;
        List<String> accumulatedStepObservations = new ArrayList<>();

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
            String obs;
            if ("APPROVED".equalsIgnoreCase(humanDecision)) {
                String finalArgs = (modifiedToolArgs != null && !modifiedToolArgs.trim().isEmpty()) ? modifiedToolArgs : resumedToolArgs;
                if (toolRegistry.isMutating(resumedToolName)) {
                    log.info("MUTATING ACTION: Clearing memento checkpoints at side-effect boundary.");
                    sharedContext.clearCheckpoints();
                }
                try {
                    String toolResult = toolExecutor.execute(resumedToolName, finalArgs);
                    String truncatedResult = observationTruncator.truncate(toolResult, 2000);
                    obs = "Action [" + resumedToolName + "] Result: " + truncatedResult;
                } catch (Exception e) {
                    String errorMsg = "FAILED: Tool execution encountered an error: " + e.getMessage();
                    log.error("Tool execution error inside step " + step.stepId(), e);
                    obs = "Action [" + resumedToolName + "] Result: " + errorMsg;
                }
            } else if ("REJECTED".equalsIgnoreCase(humanDecision)) {
                obs = "Action [" + resumedToolName + "] Result: REJECTED BY USER" + (humanFeedback != null ? ": " + humanFeedback : "");
            } else { // FEEDBACK
                obs = "Action [" + resumedToolName + "] Result: SUSPENDED. Human feedback provided: " + (humanFeedback != null ? humanFeedback : "");
            }
            localObservations.add(obs);
            accumulatedStepObservations.add(obs);
            localActionCount++;
        }

        while (!stepComplete && !sharedContext.hasExceededLimits()) {
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
                        // Mutating Checkpoint boundary management
                        if (toolRegistry.isMutating(action.toolName())) {
                            log.info("MUTATING ACTION: Clearing memento checkpoints at side-effect boundary.");
                            sharedContext.clearCheckpoints();
                        }

                        String obs;
                        try {
                            String toolResult = toolExecutor.execute(action.toolName(), action.jsonArgs());
                            String truncatedResult = observationTruncator.truncate(toolResult, 2000);
                            obs = "Action [" + action.toolName() + "] Result: " + truncatedResult;
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
                    return new StepResult(step.stepId(), error.errorMessage(), true, accumulatedStepObservations, localActionCount);
                }
            }
        }

        return new StepResult(step.stepId(), "Step action count limit exceeded.", false, accumulatedStepObservations, localActionCount);
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