package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

public class LlmFailoverIntegrationTest {

    private LlmProviderRegistry mockRegistry;
    private ContextManager mockContextManager;
    private RetryRegistry mockRetryRegistry;
    private ResilientLlmRouter router;

    @BeforeEach
    public void setUp() {
        mockRegistry = mock(LlmProviderRegistry.class);
        mockContextManager = mock(ContextManager.class);
        mockRetryRegistry = mock(RetryRegistry.class);
        
        Retry realRetry = Retry.ofDefaults("llmProvider");
        when(mockRetryRegistry.retry("llmProvider")).thenReturn(realRetry);

        LlmProperties mockProperties = mock(LlmProperties.class);
        when(mockProperties.pricing()).thenReturn(java.util.Collections.emptyMap());

        router = new ResilientLlmRouter(mockRegistry, mockContextManager, mockRetryRegistry, 
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), mockProperties);

        // Mock context manager to return messages as is
        when(mockContextManager.truncate(any(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    public void testFailoverFromPrimaryToFallback() {
        // 1. Setup two providers
        LlmProperties.ProviderConfig primary = new LlmProperties.ProviderConfig(
                "primary", "url1", "key1", true, 8192, Map.of("planner", "p1")
        );
        LlmProperties.ProviderConfig fallback = new LlmProperties.ProviderConfig(
                "fallback", "url2", "key2", false, 128000, Map.of("planner", "f1")
        );
        when(mockRegistry.getAllConfigs()).thenReturn(List.of(primary, fallback));

        // 2. Mock primary client to fail with 429
        OpenAiChatModel primaryClient = mock(OpenAiChatModel.class);
        when(mockRegistry.getClient("primary")).thenReturn(primaryClient);
        when(primaryClient.call(any(Prompt.class))).thenThrow(new RuntimeException("HTTP 429 Too Many Requests"));

        // 3. Mock fallback client to succeed
        OpenAiChatModel fallbackClient = mock(OpenAiChatModel.class);
        when(mockRegistry.getClient("fallback")).thenReturn(fallbackClient);
        
        ChatResponse successResponse = mock(ChatResponse.class);
        Generation gen = new Generation(new AssistantMessage("Fallback success"));
        when(successResponse.getResult()).thenReturn(gen);
        when(successResponse.getMetadata()).thenReturn(mock(org.springframework.ai.chat.metadata.ChatResponseMetadata.class));
        
        when(fallbackClient.call(any(Prompt.class))).thenReturn(successResponse);

        // 4. Execute
        ChatResponse response = router.generate(List.of(), TaskType.PLANNER, null);

        // 5. Verify
        assertNotNull(response);
        assertEquals("Fallback success", response.getResult().getOutput().getText());
        verify(primaryClient, times(3)).call(any(Prompt.class));
        verify(fallbackClient).call(any(Prompt.class));
    }

    @Test
    public void testRetriesFailedProviderAndThenExhausts() {
        // 1. Setup one provider
        LlmProperties.ProviderConfig primary = new LlmProperties.ProviderConfig(
                "primary", "url1", "key1", true, 8192, Map.of("planner", "p1")
        );
        when(mockRegistry.getAllConfigs()).thenReturn(List.of(primary));

        OpenAiChatModel primaryClient = mock(OpenAiChatModel.class);
        when(mockRegistry.getClient("primary")).thenReturn(primaryClient);
        // Fail with something that triggers retry
        when(primaryClient.call(any(Prompt.class))).thenThrow(new RuntimeException("Empty response from provider primary"));

        // 2. Execute - should retry and then throw
        assertThrows(RuntimeException.class, () -> router.generate(List.of(), TaskType.PLANNER, null));
        
        // Verify primary client was called multiple times (3 attempts default)
        verify(primaryClient, times(3)).call(any(Prompt.class));
    }

    @Test
    public void testReasonerTaskSkipsProjectAProviders() {
        // 1. Setup providers: project-a, project-b, and global-fallback
        LlmProperties.ProviderConfig projectA = new LlmProperties.ProviderConfig(
                "project-a-level-1", "url-a", "key-a", true, 1000000, Map.of("planner", "gemini-1.5-pro")
        );
        LlmProperties.ProviderConfig projectB = new LlmProperties.ProviderConfig(
                "project-b-level-1", "url-b", "key-b", false, 1000000, Map.of("reasoner", "gemini-1.5-flash")
        );
        LlmProperties.ProviderConfig fallback = new LlmProperties.ProviderConfig(
                "global-fallback-openrouter", "url-c", "key-c", false, 128000, Map.of("reasoner", "openrouter/auto")
        );
        when(mockRegistry.getAllConfigs()).thenReturn(List.of(projectA, projectB, fallback));

        // 2. Mock project-b client to succeed
        OpenAiChatModel projectBClient = mock(OpenAiChatModel.class);
        when(mockRegistry.getClient("project-b-level-1")).thenReturn(projectBClient);

        ChatResponse successResponse = mock(ChatResponse.class);
        Generation gen = new Generation(new AssistantMessage("Reasoner Success"));
        when(successResponse.getResult()).thenReturn(gen);
        when(successResponse.getMetadata()).thenReturn(mock(org.springframework.ai.chat.metadata.ChatResponseMetadata.class));

        when(projectBClient.call(any(Prompt.class))).thenReturn(successResponse);

        // 3. Execute with REASONER task type
        ChatResponse response = router.generate(List.of(), TaskType.REASONER, null);

        // 4. Verify
        assertNotNull(response);
        assertEquals("Reasoner Success", response.getResult().getOutput().getText());

        // Verify that project-a client was NEVER retrieved/used
        verify(mockRegistry, never()).getClient("project-a-level-1");
        verify(mockRegistry).getClient("project-b-level-1");
    }
}
