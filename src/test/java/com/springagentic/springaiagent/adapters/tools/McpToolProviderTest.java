package com.springagentic.springaiagent.adapters.tools;

import com.springagentic.springaiagent.core.sandbox.SandboxProfile;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class McpToolProviderTest {

    @Test
    public void testMcpToolProviderRegistersToolsOnStartup() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpSyncClient mcpClient = mock(McpSyncClient.class);

        Tool mockTool = mock(Tool.class);
        when(mockTool.name()).thenReturn("delete_records");
        when(mockTool.description()).thenReturn("Deletes some database records");
        when(mockTool.inputSchema()).thenReturn(Map.of("type", "object"));

        ListToolsResult result = mock(ListToolsResult.class);
        when(result.tools()).thenReturn(List.of(mockTool));
        when(mcpClient.listTools()).thenReturn(result);

        org.springframework.context.ApplicationContext mockContext = mock(org.springframework.context.ApplicationContext.class);
        when(mockContext.getBeansOfType(McpSyncClient.class)).thenReturn(Map.of("mcpClient", mcpClient));
        McpToolProvider provider = new McpToolProvider(registry, mockContext);
        provider.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(registry).registerTool(
                eq("delete_records"),
                eq("Deletes some database records"),
                any(String.class),
                eq(true), // isMutating
                eq(true), // requiresApproval
                eq(SandboxProfile.COMPUTE)
        );
    }

    @Test
    public void testMcpToolProviderRegistersNetworkToolsWithFetchProfile() {
        ToolRegistry registry = mock(ToolRegistry.class);
        McpSyncClient mcpClient = mock(McpSyncClient.class);

        Tool mockTool = mock(Tool.class);
        when(mockTool.name()).thenReturn("web_search");
        when(mockTool.description()).thenReturn("Performs a search on the web");
        when(mockTool.inputSchema()).thenReturn(Map.of("type", "object"));

        ListToolsResult result = mock(ListToolsResult.class);
        when(result.tools()).thenReturn(List.of(mockTool));
        when(mcpClient.listTools()).thenReturn(result);

        org.springframework.context.ApplicationContext mockContext = mock(org.springframework.context.ApplicationContext.class);
        when(mockContext.getBeansOfType(McpSyncClient.class)).thenReturn(Map.of("mcpClient", mcpClient));
        McpToolProvider provider = new McpToolProvider(registry, mockContext);
        provider.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(registry).registerTool(
                eq("web_search"),
                eq("Performs a search on the web"),
                any(String.class),
                eq(false), // isMutating (contains search/run verb)
                eq(false), // requiresApproval
                eq(SandboxProfile.FETCH) // Should be FETCH profile
        );
    }
}
