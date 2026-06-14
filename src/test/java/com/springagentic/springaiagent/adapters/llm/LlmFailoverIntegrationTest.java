package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
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
    private ResilientLlmRouter router;

    @BeforeEach
    public void setUp() {
        mockRegistry = mock(LlmProviderRegistry.class);
        mockContextManager = mock(ContextManager.class);
        router = new ResilientLlmRouter(mockRegistry, mockContextManager);

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
        when(primaryClient.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("HTTP 429 Too Many Requests")));

        // 3. Mock fallback client to succeed
        OpenAiChatModel fallbackClient = mock(OpenAiChatModel.class);
        when(mockRegistry.getClient("fallback")).thenReturn(fallbackClient);
        
        ChatResponse successResponse = mock(ChatResponse.class);
        Generation gen = new Generation(new AssistantMessage("Fallback success"));
        when(successResponse.getResult()).thenReturn(gen);
        when(successResponse.getMetadata()).thenReturn(mock(org.springframework.ai.chat.metadata.ChatResponseMetadata.class));
        
        when(fallbackClient.stream(any(Prompt.class))).thenReturn(Flux.just(successResponse));

        // 4. Execute
        ChatResponse response = router.generate(List.of(), TaskType.PLANNER);

        // 5. Verify
        assertNotNull(response);
        assertEquals("Fallback success", response.getResult().getOutput().getText());
        verify(primaryClient).stream(any(Prompt.class));
        verify(fallbackClient).stream(any(Prompt.class));
    }

    @Test
    public void testCooldownPreventsRetryingFailedProvider() {
        // 1. Setup one provider
        LlmProperties.ProviderConfig primary = new LlmProperties.ProviderConfig(
                "primary", "url1", "key1", true, 8192, Map.of("planner", "p1")
        );
        when(mockRegistry.getAllConfigs()).thenReturn(List.of(primary));

        OpenAiChatModel primaryClient = mock(OpenAiChatModel.class);
        when(mockRegistry.getClient("primary")).thenReturn(primaryClient);
        when(primaryClient.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("HTTP 429")));

        // 2. First call triggers cooldown
        assertThrows(RuntimeException.class, () -> router.generate(List.of(), TaskType.PLANNER));

        // 3. Second call should skip because of cooldown
        RuntimeException ex = assertThrows(RuntimeException.class, () -> router.generate(List.of(), TaskType.PLANNER));
        assertTrue(ex.getMessage().contains("exhausted"));
        
        // Verify primary client stream was only called once
        verify(primaryClient, times(1)).stream(any(Prompt.class));
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

        when(projectBClient.stream(any(Prompt.class))).thenReturn(Flux.just(successResponse));

        // 3. Execute with REASONER task type
        ChatResponse response = router.generate(List.of(), TaskType.REASONER);

        // 4. Verify
        assertNotNull(response);
        assertEquals("Reasoner Success", response.getResult().getOutput().getText());

        // Verify that project-a client was NEVER retrieved/used
        verify(mockRegistry, never()).getClient("project-a-level-1");
        verify(mockRegistry).getClient("project-b-level-1");
    }
}
