package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.spi.LlmProvider;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class TaskRouterTest {

    private LlmProvider mockLlmProvider;
    private ExecutionEngine mockExecutionEngine;
    private TaskRouter taskRouter;
    private AgentDefinition mockAgentDef;

    @BeforeEach
    public void setUp() {
        mockLlmProvider = mock(LlmProvider.class);
        mockExecutionEngine = mock(ExecutionEngine.class);
        taskRouter = new TaskRouter(mockLlmProvider, mockExecutionEngine);
        mockAgentDef = new AgentDefinition("agent-01", "You are a helpful assistant.", Collections.emptyList(), 0.7);
    }

    @Test
    public void testRouteAndExecuteSimpleExact() {
        when(mockLlmProvider.structuredRequest(any(), any(), eq(String.class)))
                .thenReturn("SIMPLE")
                .thenReturn("Hello! Answering in exactly five words.");

        AgentContext result = taskRouter.routeAndExecute("thread-123", "Hello! Please reply in exactly 5 words.", mockAgentDef);

        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("Hello! Answering in exactly five words.", result.getFinalConclusion());
        verify(mockExecutionEngine, never()).runComplexTask(any(), any(), any());
    }

    @Test
    public void testRouteAndExecuteSimpleWithPreambleAndThink() {
        // Mock LLM returning thinking block and additional characters
        when(mockLlmProvider.structuredRequest(any(), any(), eq(String.class)))
                .thenReturn("<think>Some thoughts about simplicity</think> \"SIMPLE\".")
                .thenReturn("Hi there!");

        AgentContext result = taskRouter.routeAndExecute("thread-123", "Hi", mockAgentDef);

        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals("Hi there!", result.getFinalConclusion());
        verify(mockExecutionEngine, never()).runComplexTask(any(), any(), any());
    }

    @Test
    public void testRouteAndExecuteComplex() {
        when(mockLlmProvider.structuredRequest(any(), any(), eq(String.class)))
                .thenReturn("COMPLEX");
        
        AgentContext mockContext = new AgentContext("thread-123", "Get postgres records");
        mockContext.setStatus("SUCCESS");
        when(mockExecutionEngine.runComplexTask(any(), any(), any())).thenReturn(mockContext);

        AgentContext result = taskRouter.routeAndExecute("thread-123", "Get postgres records", mockAgentDef);

        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        verify(mockExecutionEngine, times(1)).runComplexTask(eq("thread-123"), eq("Get postgres records"), eq(mockAgentDef));
    }
}
