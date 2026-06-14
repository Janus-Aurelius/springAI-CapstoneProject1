package com.springagentic.springaiagent.adapters.tools;

import com.springagentic.springaiagent.adapters.memory.RedisAgentMemoryRestClient;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.framework.registry.dto.RedisAgentMemoryDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class RedisMemoryToolExecutorTest {

    private ToolRegistry mockToolRegistry;
    private RedisAgentMemoryRestClient mockRestClient;
    private RedisMemoryToolExecutor executor;

    @BeforeEach
    public void setUp() {
        mockToolRegistry = mock(ToolRegistry.class);
        mockRestClient = mock(RedisAgentMemoryRestClient.class);
        executor = new RedisMemoryToolExecutor(mockToolRegistry, mockRestClient);
    }

    @Test
    public void testSupports() {
        assertTrue(executor.supports("save_memory"));
        assertTrue(executor.supports("search_memory"));
        assertFalse(executor.supports("unknown_tool"));
    }

    @Test
    public void testSaveMemory() {
        String jsonArgs = "{\"text\": \"Spring AI Capstone Project is active\"}";
        String result = executor.execute("save_memory", jsonArgs);

        assertTrue(result.contains("success"));
        assertTrue(result.contains("Memory saved successfully"));

        ArgumentCaptor<AddLongTermMemoryRequest> captor = ArgumentCaptor.forClass(AddLongTermMemoryRequest.class);
        verify(mockRestClient, times(1)).addLongTermMemories(captor.capture());

        List<LongTermMemory> memories = captor.getValue().memories();
        assertEquals(1, memories.size());
        assertEquals("Spring AI Capstone Project is active", memories.get(0).text());
    }

    @Test
    public void testSearchMemory() {
        String jsonArgs = "{\"query\": \"Capstone\"}";
        List<LongTermMemory> searchResults = List.of(
            new LongTermMemory("123", "Spring AI Capstone Project is active")
        );
        SearchResponse mockResponse = new SearchResponse(searchResults);
        when(mockRestClient.searchLongTermMemory(any())).thenReturn(mockResponse);

        String result = executor.execute("search_memory", jsonArgs);

        assertTrue(result.contains("Spring AI Capstone Project is active"));
        verify(mockRestClient, times(1)).searchLongTermMemory(any());
    }
}
