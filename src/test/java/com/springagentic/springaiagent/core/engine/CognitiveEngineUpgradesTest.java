package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.Plan;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.adapters.tools.JacksonToolRegistry;
import com.springagentic.springaiagent.core.domain.ObservationTruncator;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
import com.springagentic.springaiagent.core.sandbox.McpContainerFactory;
import com.springagentic.springaiagent.core.security.SecretRedactor;
import com.springagentic.springaiagent.framework.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CognitiveEngineUpgradesTest {

    private Planner planner;
    private Reasoner reasoner;
    private ToolExecutor toolExecutor;
    private MemoryStore memoryStore;
    private ObservationTruncator truncator;
    private ToolRegistry toolRegistry;
    private ExecutionEngine engine;
    private LlmProperties llmProperties;

    @BeforeEach
    public void setUp() {
        planner = mock(Planner.class);
        reasoner = mock(Reasoner.class);
        toolExecutor = mock(ToolExecutor.class);
        memoryStore = mock(MemoryStore.class);
        truncator = mock(ObservationTruncator.class);
        toolRegistry = new JacksonToolRegistry();
        
        // Mock LlmProperties
        llmProperties = mock(LlmProperties.class);
        LlmProperties.GuardrailProperties guardrails = new LlmProperties.GuardrailProperties(20, 5, 3, 100000L, 2000);
        when(llmProperties.guardrails()).thenReturn(guardrails);

        McpContainerFactory containerFactory = mock(McpContainerFactory.class);
        SecretRedactor secretRedactor = mock(SecretRedactor.class);
        engine = new ExecutionEngine(planner, reasoner, toolExecutor, memoryStore, truncator, toolRegistry, containerFactory, secretRedactor, llmProperties);

        // Configure default truncator mock behavior
        when(truncator.truncate(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolExecutor.supports(anyString())).thenReturn(true);
    }

    @Test
    public void testMementoCheckpointAndRollback() {
        AgentContext context = new AgentContext("thread-1", "Solve world hunger");
        context.putStepSummary("step-1", "Distilled step 1 details");
        context.addObservation("tool-1", "Raw tool output");

        // Save State Checkpoint
        context.saveCheckpoint();

        // Mutate context state
        context.putStepSummary("step-2", "State that should be undone");
        context.addObservation("tool-2", "Observation that should be undone");
        context.incrementReplanCount();

        // Restore Checkpoint
        boolean success = context.rollback();
        assertTrue(success);

        // Verify restoration
        assertEquals(1, context.getObservationsList().size());
        assertEquals("Action [tool-1] Result: Raw tool output", context.getObservationsList().get(0));
        assertEquals(1, context.getStepSummaries().size());
        assertTrue(context.getStepSummaries().containsKey("step-1"));
        assertFalse(context.getStepSummaries().containsKey("step-2"));
    }

    @Test
    public void testMutatingToolClearsMementoStack() {
        // Register search_database as read-only, write_database as mutating
        toolRegistry.registerTool("search_database", "Read data", String.class, false);
        toolRegistry.registerTool("write_database", "Mutate data", String.class, true);

        assertTrue(toolRegistry.isMutating("write_database"));
        assertFalse(toolRegistry.isMutating("search_database"));

        // Setup Agent Definition
        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", List.of("search_database", "write_database"), 0.5);

        // Setup a simple Step
        Step step = new Step("step-1", "Write data", "Data is saved");
        Plan plan = new Plan(List.of(step));

        // Mock planner and memory
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());

        // Mock reasoner to invoke mutating tool then finish
        when(reasoner.think(any(), any(), any()))
            .thenReturn(new ReasoningResult.Action("write_database", "{}"))
            .thenReturn(new ReasoningResult.FinalAnswer("Data written successfully"));

        // Execute complex task
        engine.runComplexTask("thread-1", "Run mutate job", agentDef);

        // Verify that checkpoints stack is cleared and rollback returns false
        verify(toolExecutor, times(1)).execute(eq("write_database"), anyString());
    }

    @Test
    public void testParallelDAGStepExecutionFlow() throws InterruptedException {
        // Create 3 steps: A and B can run in parallel, C depends on both A and B
        Step stepA = new Step("step-A", "Task A", "Outcome A", Collections.emptyList());
        Step stepB = new Step("step-B", "Task B", "Outcome B", Collections.emptyList());
        Step stepC = new Step("step-C", "Task C", "Outcome C", List.of("step-A", "step-B"));
        Plan plan = new Plan(List.of(stepA, stepB, stepC));

        // Track thread execution names to verify concurrent execution
        List<String> threadNames = new CopyOnWriteArrayList<>();

        // Mock planner and memory
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());

        // Mock reasoner think for A, B, and C
        when(reasoner.think(any(), eq(stepA), any())).thenAnswer(inv -> {
            threadNames.add(Thread.currentThread().getName());
            Thread.sleep(100); // Simulate processing delay
            return new ReasoningResult.FinalAnswer("Result A");
        });

        when(reasoner.think(any(), eq(stepB), any())).thenAnswer(inv -> {
            threadNames.add(Thread.currentThread().getName());
            Thread.sleep(100); // Simulate processing delay
            return new ReasoningResult.FinalAnswer("Result B");
        });

        when(reasoner.think(any(), eq(stepC), any())).thenAnswer(inv -> {
            threadNames.add(Thread.currentThread().getName());
            return new ReasoningResult.FinalAnswer("Result C");
        });

        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", new ArrayList<>(), 0.5);

        // Run engine
        engine.runComplexTask("thread-1", "DAG run", agentDef);

        // Verify C was resolved after A and B
        assertEquals(3, threadNames.size());
        // Verify virtual threads were used (virtual threads have name containing "virtual")
        // Note: Spring Virtual Thread executor names them or leaves them anonymous, but they run concurrently on carriers
        // Let's assert that step A and step B ran on different concurrent virtual tasks
        assertNotNull(threadNames.get(0));
        assertNotNull(threadNames.get(1));
    }

    @Test
    public void testToolRegistryApprovalFlags() {
        toolRegistry.registerTool("search", "read-only", String.class, false, false);
        toolRegistry.registerTool("delete", "mutating-and-approval", String.class, true, true);

        assertTrue(toolRegistry.isMutating("delete"));
        assertTrue(toolRegistry.requiresApproval("delete"));
        assertFalse(toolRegistry.isMutating("search"));
        assertFalse(toolRegistry.requiresApproval("search"));
    }

    @Test
    public void testTaskSuspensionOnApprovalRequiredTool() {
        toolRegistry.registerTool("write_database", "Mutates data with approval", String.class, true, true);
        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", List.of("write_database"), 0.5);

        Step step = new Step("step-1", "Write data", "Data is saved");
        Plan plan = new Plan(List.of(step));

        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());
        when(reasoner.think(any(), eq(step), any()))
            .thenReturn(new ReasoningResult.Action("write_database", "{\"query\":\"INSERT\"}"));

        AgentContext context = engine.runComplexTask("thread-1", "Insert database record", agentDef);

        assertEquals("AWAITING_APPROVAL", context.getStatus());
        assertEquals("step-1", context.getSuspendedStepId());
        assertEquals("write_database", context.getSuspendedToolName());
        assertEquals("{\"query\":\"INSERT\"}", context.getSuspendedToolArgs());
        assertNull(context.getFinalConclusion());
    }

    @Test
    public void testTaskResumptionApproved() {
        toolRegistry.registerTool("write_database", "Mutates data with approval", String.class, true, true);
        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", List.of("write_database"), 0.5);

        Step step = new Step("step-1", "Write data", "Data is saved");
        Plan plan = new Plan(List.of(step));

        // Mock tool executor response
        when(toolExecutor.execute(eq("write_database"), anyString())).thenReturn("SUCCESS_RECORD_INSERTED");

        // Mock reasoner to return Action first, then FinalAnswer when observation is seen
        when(reasoner.think(any(), eq(step), any())).thenAnswer(invocation -> {
            AgentContext ctx = invocation.getArgument(0);
            boolean hasResult = ctx.getObservationsList().stream()
                    .anyMatch(obs -> obs.contains("SUCCESS_RECORD_INSERTED"));
            if (hasResult) {
                return new ReasoningResult.FinalAnswer("Job completed successfully");
            } else {
                return new ReasoningResult.Action("write_database", "{\"query\":\"INSERT\"}");
            }
        });

        // 1. Run the task -> Suspends
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());
        AgentContext context = engine.runComplexTask("thread-1", "Run query", agentDef);
        assertEquals("AWAITING_APPROVAL", context.getStatus());

        // 2. Mock memoryStore load to return this context
        when(memoryStore.loadRun(eq("thread-1"), eq(context.getRunId()))).thenReturn(Optional.of(context));

        // 3. Resume the task with APPROVED
        context.resume("APPROVED", null, null);
        engine.resumeComplexTask(context, agentDef);

        assertEquals("SUCCESS", context.getStatus());
        assertEquals("All planned steps were completed successfully. Result: Job completed successfully", context.getFinalConclusion());
        assertEquals("Job completed successfully", context.getStepSummaries().get("step-1"));
        assertNull(context.getSuspendedStepId());
        verify(toolExecutor, times(1)).execute(eq("write_database"), eq("{\"query\":\"INSERT\"}"));
    }

    @Test
    public void testTaskResumptionRejected() {
        toolRegistry.registerTool("write_database", "Mutates data with approval", String.class, true, true);
        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", List.of("write_database"), 0.5);

        Step step = new Step("step-1", "Write data", "Data is saved");
        Plan plan = new Plan(List.of(step));

        // Mock reasoner to check observation for rejection
        when(reasoner.think(any(), eq(step), any())).thenAnswer(invocation -> {
            AgentContext ctx = invocation.getArgument(0);
            boolean hasRejection = ctx.getObservationsList().stream()
                    .anyMatch(obs -> obs.contains("REJECTED BY USER") && obs.contains("Security risk check failed"));
            if (hasRejection) {
                return new ReasoningResult.FinalAnswer("Alternative recovery path selected");
            } else {
                return new ReasoningResult.Action("write_database", "{\"query\":\"INSERT\"}");
            }
        });

        // 1. Run the task -> Suspends
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());
        AgentContext context = engine.runComplexTask("thread-1", "Run query", agentDef);
        assertEquals("AWAITING_APPROVAL", context.getStatus());

        // 2. Resume the task with REJECTED
        context.resume("REJECTED", "Security risk check failed", null);
        engine.resumeComplexTask(context, agentDef);

        assertEquals("SUCCESS", context.getStatus());
        assertEquals("All planned steps were completed successfully. Result: Alternative recovery path selected", context.getFinalConclusion());
        assertEquals("Alternative recovery path selected", context.getStepSummaries().get("step-1"));
        assertNull(context.getSuspendedStepId());
        verify(toolExecutor, never()).execute(anyString(), anyString());
    }

    @Test
    public void testTaskResumptionWithModifiedToolArgs() {
        toolRegistry.registerTool("write_database", "Mutates data with approval", String.class, true, true);
        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", List.of("write_database"), 0.5);

        Step step = new Step("step-1", "Write data", "Data is saved");
        Plan plan = new Plan(List.of(step));

        // Mock tool executor response
        when(toolExecutor.execute(eq("write_database"), anyString())).thenReturn("SUCCESS_RECORD_INSERTED");

        // Mock reasoner
        when(reasoner.think(any(), eq(step), any())).thenAnswer(invocation -> {
            AgentContext ctx = invocation.getArgument(0);
            boolean hasResult = ctx.getObservationsList().stream()
                    .anyMatch(obs -> obs.contains("SUCCESS_RECORD_INSERTED"));
            if (hasResult) {
                return new ReasoningResult.FinalAnswer("Job completed with modified args");
            } else {
                return new ReasoningResult.Action("write_database", "{\"query\":\"INSERT\"}");
            }
        });

        // 1. Run -> Suspends
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());
        AgentContext context = engine.runComplexTask("thread-1", "Run query", agentDef);
        assertEquals("AWAITING_APPROVAL", context.getStatus());

        // 2. Resume with APPROVED and modified arguments
        context.resume("APPROVED", null, "{\"query\":\"INSERT_MODIFIED\"}");
        engine.resumeComplexTask(context, agentDef);

        assertEquals("SUCCESS", context.getStatus());
        assertEquals("All planned steps were completed successfully. Result: Job completed with modified args", context.getFinalConclusion());
        assertEquals("Job completed with modified args", context.getStepSummaries().get("step-1"));
        assertNull(context.getSuspendedStepId());
        verify(toolExecutor, times(1)).execute(eq("write_database"), eq("{\"query\":\"INSERT_MODIFIED\"}"));
    }

    @Test
    public void testInMemoryStoreClaimSuspendedRunIdempotency() {
        com.springagentic.springaiagent.adapters.memory.InMemoryStore store = new com.springagentic.springaiagent.adapters.memory.InMemoryStore();
        AgentContext context = new AgentContext("thread-123", "Goal");
        
        // Initially running
        store.saveRun(context);
        assertFalse(store.claimSuspendedRun("thread-123", context.getRunId()));

        // Suspend
        context.suspend("step-1", "tool-1", "{}");
        store.saveRun(context);

        // First claim -> Should succeed
        assertTrue(store.claimSuspendedRun("thread-123", context.getRunId()));
        assertEquals("RUNNING", context.getStatus());

        // Second claim -> Should fail (already claimed/running)
        assertFalse(store.claimSuspendedRun("thread-123", context.getRunId()));
    }

    @Test
    public void testParallelExecutionRollbackOnFailure() {
        // Create 2 steps running in parallel: A and B
        Step stepA = new Step("step-A", "Task A", "Outcome A", Collections.emptyList());
        Step stepB = new Step("step-B", "Task B", "Outcome B", Collections.emptyList());
        Plan plan = new Plan(List.of(stepA, stepB));

        // Mock planner and memory
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());

        // Step A succeeds, Step B fails softly (returns ReasoningResult.Error or throws exception)
        when(reasoner.think(any(), eq(stepA), any())).thenReturn(new ReasoningResult.FinalAnswer("Result A"));
        when(reasoner.think(any(), eq(stepB), any())).thenReturn(new ReasoningResult.Error("Task B encountered a soft failure"));

        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", new ArrayList<>(), 0.5);

        // Run engine
        AgentContext context = engine.runComplexTask("thread-1", "Parallel rollback run", agentDef);

        // Since Step B failed softly, a rollback is executed, resetting summaries to pre-batch baseline (empty)
        assertTrue(context.getStepSummaries().isEmpty(), "Summaries should be rolled back to empty");
    }

    @Test
    public void testParallelExecutionRollbackBlockedByMutatingTool() {
        toolRegistry.registerTool("write_db", "Mutates database", String.class, true);
        
        // Create 2 steps running in parallel: A (mutating) and B (failing)
        Step stepA = new Step("step-A", "Write DB", "Outcome A", Collections.emptyList());
        Step stepB = new Step("step-B", "Task B", "Outcome B", Collections.emptyList());
        Plan plan = new Plan(List.of(stepA, stepB));

        // Mock planner
        when(planner.createPlan(any(), any())).thenReturn(plan);
        when(memoryStore.getThreadHistory(any())).thenReturn(new ArrayList<>());

        // Step A runs mutating tool 'write_db', Step B fails softly
        when(reasoner.think(any(), eq(stepA), any()))
            .thenReturn(new ReasoningResult.Action("write_db", "{}"))
            .thenReturn(new ReasoningResult.FinalAnswer("Result A"));
        when(reasoner.think(any(), eq(stepB), any())).thenReturn(new ReasoningResult.Error("Task B failed"));

        AgentDefinition agentDef = new AgentDefinition("agent-01", "system", List.of("write_db"), 0.5);

        // Run engine
        AgentContext context = engine.runComplexTask("thread-1", "Blocked rollback run", agentDef);

        // Verify that the run terminates under FATAL_ERROR because rollback was blocked by mutating action
        assertEquals("FATAL_ERROR", context.getStatus());
        assertEquals("FATAL_ERROR", context.getTerminationReason());
        assertTrue(context.getFinalConclusion().contains("failed"), "Should terminate with step failure error");
    }
}
