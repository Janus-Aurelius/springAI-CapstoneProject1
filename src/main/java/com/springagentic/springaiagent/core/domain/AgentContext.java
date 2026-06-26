package com.springagentic.springaiagent.core.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.UUID;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class AgentContext {
    private String threadId;  // Grouping for persistent history (The Conversation)
    private String runId;     // Unique ID for THIS specific concurrent execution
    private String userGoal;

    private Plan plan;
    @com.fasterxml.jackson.annotation.JsonProperty("observations")
    private final List<String> observations = new ArrayList<>();
    private final Map<String, String> stepSummaries = new ConcurrentHashMap<>();

    @com.fasterxml.jackson.annotation.JsonIgnore
    private final Deque<AgentContextMemento> mementoStack = new ArrayDeque<>();

    // Private constructor for Jackson deserialization
    private AgentContext() {}

    // Guardrails / Termination tracking
    private int totalReplanCount = 0;
    private int totalActionCount = 0;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private final java.util.concurrent.atomic.AtomicLong totalTokensConsumed = new java.util.concurrent.atomic.AtomicLong(0);

    @com.fasterxml.jackson.annotation.JsonIgnore
    private final java.util.concurrent.atomic.DoubleAdder totalCostUsd = new java.util.concurrent.atomic.DoubleAdder();

    private String finalConclusion;
    private String terminationReason; // e.g., "SUCCESS", "MAX_LOOPS_REACHED", "FATAL_ERROR"

    private final List<String> actionHistory = new ArrayList<>(); // Track (toolName + observation) hashes

    private String status = "RUNNING"; // RUNNING, AWAITING_APPROVAL, SUCCESS, FAILED, etc.
    private String suspendedStepId;
    private String suspendedToolName;
    private String suspendedToolArgs;
    private String humanDecision;
    private String humanFeedback;
    private String modifiedToolArgs;
    private String parentTraceId;
    private String parentSpanId;

    public synchronized void saveCheckpoint() {
        this.mementoStack.push(new AgentContextMemento(
            this.plan,
            new ArrayList<>(this.observations),
            new HashMap<>(this.stepSummaries),
            this.totalReplanCount,
            this.totalActionCount
        ));
    }

    public synchronized boolean rollback() {
        if (this.mementoStack.isEmpty()) {
            return false;
        }
        AgentContextMemento memento = this.mementoStack.pop();
        this.plan = memento.plan();
        this.observations.clear();
        this.observations.addAll(memento.observations());
        this.stepSummaries.clear();
        this.stepSummaries.putAll(memento.stepSummaries());
        this.totalReplanCount = memento.totalReplanCount();
        this.totalActionCount = memento.totalActionCount();
        return true;
    }

    public synchronized void clearCheckpoints() {
        this.mementoStack.clear();
    }

    public synchronized void discardCheckpoint() {
        if (!this.mementoStack.isEmpty()) {
            this.mementoStack.pop();
        }
    }

    public void putStepSummary(String stepId, String summary) {
        this.stepSummaries.put(stepId, summary);
    }

    public Map<String, String> getStepSummaries() {
        return this.stepSummaries;
    }

    public AgentContext(String threadId, String userGoal) {
        this.threadId = threadId;
        this.runId = UUID.randomUUID().toString();
        this.userGoal = userGoal;
    }

    // --- Getters ---
    public String getThreadId() { return threadId; }
    public String getRunId() { return runId; }
    public String getUserGoal() { return userGoal; }
    public Plan getPlan() { return plan; }

    // --- Setters / Modifiers ---
    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public void addObservation(String toolName, String result) {
        this.observations.add("Action [" + toolName + "] Result: " + result);
        this.totalActionCount++;
        // Track for stagnation: hash of tool + result
        this.actionHistory.add(Integer.toHexString((toolName + result).hashCode()));
    }

    public boolean isStagnated(int threshold) {
        if (actionHistory.size() < threshold) return false;
        String last = actionHistory.get(actionHistory.size() - 1);
        for (int i = 1; i < threshold; i++) {
            if (!last.equals(actionHistory.get(actionHistory.size() - 1 - i))) {
                return false;
            }
        }
        return true;
    }

    public void addTokens(long tokens) {
        this.totalTokensConsumed.addAndGet(tokens);
    }

    public void addCost(double cost) {
        this.totalCostUsd.add(cost);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("totalCostUsd")
    public double getTotalCostUsd() {
        return totalCostUsd.sum();
    }

    @com.fasterxml.jackson.annotation.JsonProperty("totalCostUsd")
    public void setTotalCostUsd(double val) {
        totalCostUsd.reset();
        totalCostUsd.add(val);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("totalTokensConsumed")
    public long getTotalTokensConsumed() {
        return totalTokensConsumed.get();
    }

    @com.fasterxml.jackson.annotation.JsonProperty("totalTokensConsumed")
    public void setTotalTokensConsumed(long val) {
        totalTokensConsumed.set(val);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<String> getObservationsList() {
        return this.observations;
    }

    public void addObservationRaw(String obs) {
        this.observations.add(obs);
    }

    public void incrementActionCount(int count) {
        this.totalActionCount += count;
    }

    public void incrementReplanCount() {
        this.totalReplanCount++;
    }

    // --- Guardrail Checks ---
    public boolean hasExceededLimits(int maxActions, int maxReplans, long maxTokenBudget) {
        return totalActionCount > maxActions || totalReplanCount > maxReplans || totalTokensConsumed.get() > maxTokenBudget;
    }

    public void terminate(String reason, String conclusion) {
        this.terminationReason = reason;
        this.finalConclusion = conclusion;
        this.status = reason;
    }

    public boolean isTerminated() {
        return terminationReason != null;
    }

    public String getFinalConclusion() { return finalConclusion; }

    public int getReplanCount() { return totalReplanCount; }

    public String getTerminationReason() { return terminationReason; }

    public Object getObservations() {
        return this.observations;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void suspend(String stepId, String toolName, String toolArgs) {
        this.status = "AWAITING_APPROVAL";
        this.suspendedStepId = stepId;
        this.suspendedToolName = toolName;
        this.suspendedToolArgs = toolArgs;
        this.humanDecision = null;
        this.humanFeedback = null;
    }

    public void resume(String decision, String feedback, String modifiedToolArgs) {
        this.status = "RUNNING";
        this.humanDecision = decision;
        this.humanFeedback = feedback;
        this.modifiedToolArgs = modifiedToolArgs;
    }

    public void clearSuspension() {
        this.suspendedStepId = null;
        this.suspendedToolName = null;
        this.suspendedToolArgs = null;
        this.humanDecision = null;
        this.humanFeedback = null;
        this.modifiedToolArgs = null;
    }

    public String getSuspendedStepId() { return suspendedStepId; }
    public String getSuspendedToolName() { return suspendedToolName; }
    public String getSuspendedToolArgs() { return suspendedToolArgs; }
    public String getHumanDecision() { return humanDecision; }
    public String getHumanFeedback() { return humanFeedback; }
    public String getModifiedToolArgs() { return modifiedToolArgs; }

    public String getParentTraceId() { return parentTraceId; }
    public void setParentTraceId(String parentTraceId) { this.parentTraceId = parentTraceId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
}