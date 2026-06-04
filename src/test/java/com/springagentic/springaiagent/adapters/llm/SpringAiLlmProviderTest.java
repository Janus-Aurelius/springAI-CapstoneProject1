package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class SpringAiLlmProviderTest {

    private ChatClient mockPrimaryClient;
    private ChatClient mockFallbackClient;
    private ToolRegistry mockToolRegistry;
    private SpringAiLlmProvider llmProvider;

    @BeforeEach
    public void setUp() {
        mockPrimaryClient = mock(ChatClient.class);
        mockFallbackClient = mock(ChatClient.class);
        mockToolRegistry = mock(ToolRegistry.class);
        
        when(mockToolRegistry.getSchemas(any())).thenReturn(Collections.emptyList());

        llmProvider = new SpringAiLlmProvider(mockPrimaryClient, mockFallbackClient, mockToolRegistry);
    }

    private void setupMockClientResponse(ChatClient client, String firstResponse, String secondResponse) {
        ChatClientRequestSpec promptSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callResponse = mock(CallResponseSpec.class);

        when(client.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponse);

        if (secondResponse != null) {
            when(callResponse.content())
                .thenReturn(firstResponse)
                .thenReturn(secondResponse);
        } else {
            when(callResponse.content()).thenReturn(firstResponse);
        }
    }

    @Test
    public void testSelfCorrectionSucceedsOnRetry() {
        // First response is malformed, second is valid JSON
        setupMockClientResponse(
            mockPrimaryClient,
            "invalid-json-content",
            "{\"type\": \"FINAL_ANSWER\", \"text\": \"Deduction successful after retry\"}"
        );

        AgentContext context = new AgentContext("thread-1", "Solve issue");
        Step step = new Step("step-1", "Resolve formatting error", "Outcome");

        ReasoningResult result = llmProvider.think(context, step, new ArrayList<>());

        // Verify result is successful final answer
        assertTrue(result instanceof ReasoningResult.FinalAnswer);
        assertEquals("Deduction successful after retry", ((ReasoningResult.FinalAnswer) result).text());

        // Verify primary client prompt user spec is captured
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockPrimaryClient.prompt(), times(2)).user(userPromptCaptor.capture());
        
        List<String> userPrompts = userPromptCaptor.getAllValues();
        assertEquals("What is your next move?", userPrompts.get(0));
        assertTrue(userPrompts.get(1).contains("malformed JSON"));
        assertTrue(userPrompts.get(1).contains("invalid-json-content"));
    }

    @Test
    public void testFallbackClientOnSelfCorrectionExhaustion() {
        // Primary client returns malformed JSON repeatedly
        setupMockClientResponse(mockPrimaryClient, "bad-json-1", "bad-json-2");

        // Fallback client returns valid JSON
        setupMockClientResponse(mockFallbackClient, "{\"type\": \"FINAL_ANSWER\", \"text\": \"Resolved by fallback model\"}", null);

        AgentContext context = new AgentContext("thread-1", "Solve issue");
        Step step = new Step("step-1", "Solve it", "Outcome");

        ReasoningResult result = llmProvider.think(context, step, new ArrayList<>());

        // Verify fallback client was called and succeeded
        assertTrue(result instanceof ReasoningResult.FinalAnswer);
        assertEquals("Resolved by fallback model", ((ReasoningResult.FinalAnswer) result).text());

        verify(mockFallbackClient, times(1)).prompt();
    }
}
