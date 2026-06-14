package com.springagentic.springaiagent.adapters.tools;

import com.springagentic.springaiagent.core.sandbox.SandboxProfile;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpToolProvider implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(McpToolProvider.class);

    private final ToolRegistry toolRegistry;
    private final org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    public McpToolProvider(ToolRegistry toolRegistry, org.springframework.context.ApplicationContext applicationContext) {
        this.toolRegistry = toolRegistry;
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        java.util.Map<String, McpSyncClient> clientsMap = applicationContext.getBeansOfType(McpSyncClient.class);
        log.info("Bootstrapping MCP Tools. Found {} MCP client(s) in context: {}", 
                clientsMap.size(), clientsMap.keySet());
        
        for (McpSyncClient client : clientsMap.values()) {
            if (client == null) {
                continue;
            }
            try {
                var toolsResult = client.listTools();
                if (toolsResult != null && toolsResult.tools() != null) {
                    List<Tool> tools = toolsResult.tools();
                    log.info("Loaded {} tools from MCP client.", tools.size());
                    for (Tool tool : tools) {
                        registerMcpTool(tool);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to list tools from MCP client", e);
            }
        }
    }

    private void registerMcpTool(Tool tool) {
        String name = tool.name();
        String description = tool.description();
        
        String jsonSchema = "";
        if (tool.inputSchema() != null) {
            try {
                jsonSchema = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tool.inputSchema());
            } catch (Exception e) {
                log.warn("Failed to serialize input schema for tool: {}", name, e);
            }
        }

        // Explicit Tool Metadata Mapping
        SandboxProfile profile = SandboxProfile.COMPUTE;
        boolean isMutating = false;
        boolean requiresApproval = false;

        if (isFetchTool(name)) {
            profile = SandboxProfile.FETCH;
        } else if (isMutatingTool(name)) {
            isMutating = true;
            requiresApproval = true;
        }

        log.info("Registering MCP Tool: {} [Profile: {}, Mutating: {}, Approval: {}]", 
                name, profile, isMutating, requiresApproval);
        toolRegistry.registerTool(name, description, jsonSchema, isMutating, requiresApproval, profile);
    }

    private boolean isFetchTool(String name) {
        return name.contains("search") || name.contains("fetch") || name.contains("list") || 
               name.contains("get") || name.contains("read") || name.contains("navigate") || 
               name.contains("screenshot") || name.contains("query");
    }

    private boolean isMutatingTool(String name) {
        return name.contains("write") || name.contains("delete") || name.contains("update") || 
               name.contains("create") || name.contains("push") || name.contains("merge") || 
               name.contains("execute") || name.contains("fill") || name.contains("click") ||
               name.contains("hover") || name.contains("evaluate");
    }
}
