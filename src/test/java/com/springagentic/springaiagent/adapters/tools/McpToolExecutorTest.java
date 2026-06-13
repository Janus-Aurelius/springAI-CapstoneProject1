package com.springagentic.springaiagent.adapters.tools;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class McpToolExecutorTest {

    @Test
    public void testSupportsReturnsTrueForExistentTool() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Tool mockTool = mock(Tool.class);
        when(mockTool.name()).thenReturn("test_tool");
        
        ListToolsResult listResult = mock(ListToolsResult.class);
        when(listResult.tools()).thenReturn(List.of(mockTool));
        when(mockClient.listTools()).thenReturn(listResult);

        org.springframework.context.ApplicationContext mockContext = mock(org.springframework.context.ApplicationContext.class);
        when(mockContext.getBeansOfType(McpSyncClient.class)).thenReturn(Map.of("mockClient", mockClient));
        McpToolExecutor executor = new McpToolExecutor(mockContext);
        assertTrue(executor.supports("test_tool"));
        assertFalse(executor.supports("non_existent"));
    }

    @Test
    public void testExecuteDelegatesToClient() {
        McpSyncClient mockClient = mock(McpSyncClient.class);
        Tool mockTool = mock(Tool.class);
        when(mockTool.name()).thenReturn("test_tool");

        ListToolsResult listResult = mock(ListToolsResult.class);
        when(listResult.tools()).thenReturn(List.of(mockTool));
        when(mockClient.listTools()).thenReturn(listResult);

        CallToolResult callResult = mock(CallToolResult.class);
        when(callResult.isError()).thenReturn(false);
        McpSchema.TextContent content = new McpSchema.TextContent("Success Response");
        when(callResult.content()).thenReturn(List.of(content));

        when(mockClient.callTool(any(CallToolRequest.class))).thenReturn(callResult);

        org.springframework.context.ApplicationContext mockContext = mock(org.springframework.context.ApplicationContext.class);
        when(mockContext.getBeansOfType(McpSyncClient.class)).thenReturn(Map.of("mockClient", mockClient));
        McpToolExecutor executor = new McpToolExecutor(mockContext);
        executor.supports("test_tool"); // Populate mapping

        String result = executor.execute("test_tool", "{\"arg1\":\"val1\"}");
        assertEquals("Success Response", result);
        verify(mockClient, times(1)).callTool(any(CallToolRequest.class));
    }
}
