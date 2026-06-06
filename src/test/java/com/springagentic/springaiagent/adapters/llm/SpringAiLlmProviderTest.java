package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.framework.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SpringAiLlmProviderTest {

    private LlmRouter mockLlmRouter;
    private ToolRegistry mockToolRegistry;
    private LlmProperties mockLlmProperties;
    private SpringAiLlmProvider llmProvider;

    @BeforeEach
    public void setUp() {
        mockLlmRouter = mock(LlmRouter.class);
        mockToolRegistry = mock(ToolRegistry.class);
        mockLlmProperties = mock(LlmProperties.class);
        
        when(mockToolRegistry.getSchemas(any())).thenReturn(Collections.emptyList());
        when(mockLlmProperties.stripReasoning()).thenReturn(true);

        llmProvider = new SpringAiLlmProvider(mockLlmRouter, mockToolRegistry, mockLlmProperties);
    }

    private void setupMockRouterResponse(String firstResponse, String secondResponse) {
        ChatResponse res1 = mock(ChatResponse.class);
        Generation gen1 = mock(Generation.class);
        AssistantMessage msg1 = new AssistantMessage(firstResponse);
        when(res1.getResult()).thenReturn(gen1);
        when(gen1.getOutput()).thenReturn(msg1);
        
        // Mock usage/metadata
        org.springframework.ai.chat.metadata.ChatResponseMetadata metadata = mock(org.springframework.ai.chat.metadata.ChatResponseMetadata.class);
        org.springframework.ai.chat.metadata.Usage usage = mock(org.springframework.ai.chat.metadata.Usage.class);
        when(res1.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getTotalTokens()).thenReturn(100);

        if (secondResponse != null) {
            ChatResponse res2 = mock(ChatResponse.class);
            Generation gen2 = mock(Generation.class);
            AssistantMessage msg2 = new AssistantMessage(secondResponse);
            when(res2.getResult()).thenReturn(gen2);
            when(gen2.getOutput()).thenReturn(msg2);
            when(res2.getMetadata()).thenReturn(metadata);

            when(mockLlmRouter.generate(any(), any()))
                .thenReturn(res1)
                .thenReturn(res2);
        } else {
            when(mockLlmRouter.generate(any(), any())).thenReturn(res1);
        }
    }

    @Test
    public void testSelfCorrectionSucceedsOnRetry() {
        // First response is malformed, second is valid JSON
        setupMockRouterResponse(
            "invalid-json-content",
            "{\"type\": \"FINAL_ANSWER\", \"text\": \"Deduction successful after retry\"}"
        );

        AgentContext context = new AgentContext("thread-1", "Solve issue");
        Step step = new Step("step-1", "Resolve formatting error", "Outcome");

        ReasoningResult result = llmProvider.think(context, step, new ArrayList<>());

        // Verify result is successful final answer
        assertTrue(result instanceof ReasoningResult.FinalAnswer);
        assertEquals("Deduction successful after retry", ((ReasoningResult.FinalAnswer) result).text());

        // Verify router was called twice
        verify(mockLlmRouter, times(2)).generate(any(), any());
    }

    private static class AssistantMessage extends org.springframework.ai.chat.messages.AssistantMessage {
        public AssistantMessage(String content) {
            super(content);
        }
    }
}
