package com.springagentic.springaiagent.adapters.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutor.class);

    private final org.springframework.context.ApplicationContext applicationContext;
    private final Map<String, McpSyncClient> toolToClientMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public McpToolExecutor(org.springframework.context.ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("Initializing McpToolExecutor: populating tool map...");
        for (McpSyncClient client : getMcpSyncClients()) {
            if (client == null) continue;
            try {
                var toolsResult = client.listTools();
                if (toolsResult != null && toolsResult.tools() != null) {
                    for (var tool : toolsResult.tools()) {
                        toolToClientMap.put(tool.name(), client);
                        log.info("Registered MCP tool [{}]", tool.name());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to list tools from an MCP client during initialization: {}", e.getMessage());
            }
        }
    }

    private List<McpSyncClient> getMcpSyncClients() {
        return List.copyOf(applicationContext.getBeansOfType(McpSyncClient.class).values());
    }

    @Override
    public boolean supports(String toolName) {
        return toolToClientMap.containsKey(toolName);
    }

    @Override
    public String execute(String toolName, String jsonArgs) {
        McpSyncClient client = toolToClientMap.get(toolName);
        if (client == null) {
            if (!supports(toolName)) {
                throw new IllegalArgumentException("Unsupported MCP tool: " + toolName);
            }
            client = toolToClientMap.get(toolName);
        }

        log.info("Executing MCP tool [{}] via McpSyncClient", toolName);
        try {
            Map<String, Object> args = null;
            if (jsonArgs != null && !jsonArgs.trim().isEmpty()) {
                args = objectMapper.readValue(jsonArgs, Map.class);
            }

            CallToolResult result = client.callTool(new CallToolRequest(toolName, args));
            if (result == null) {
                return "{\"status\": \"error\", \"message\": \"Null result returned from MCP server\"}";
            }

            if (Boolean.TRUE.equals(result.isError())) {
                log.error("MCP tool [{}] returned an error result: {}", toolName, result.content());
            }

            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (Object contentObj : result.content()) {
                    if (contentObj instanceof McpSchema.TextContent textContent) {
                        sb.append(textContent.text());
                    } else {
                        sb.append(contentObj.toString());
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Error executing MCP tool [{}]", toolName, e);
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }
}
