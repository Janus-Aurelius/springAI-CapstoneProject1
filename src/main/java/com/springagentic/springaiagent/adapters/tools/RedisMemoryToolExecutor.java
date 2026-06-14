package com.springagentic.springaiagent.adapters.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springagentic.springaiagent.adapters.memory.RedisAgentMemoryRestClient;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.framework.registry.dto.RedisAgentMemoryDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class RedisMemoryToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryToolExecutor.class);

    private final RedisAgentMemoryRestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record SaveMemoryParams(
        @JsonPropertyDescription("The text content or fact to save to the agent's long-term memory")
        String text
    ) {}

    public record SearchMemoryParams(
        @JsonPropertyDescription("The search query text to find matching facts in the agent's long-term memory")
        String query
    ) {}

    public RedisMemoryToolExecutor(ToolRegistry toolRegistry, RedisAgentMemoryRestClient restClient) {
        this.restClient = restClient;

        // Register memory tools
        toolRegistry.registerTool(
            "save_memory",
            "Saves a new fact or piece of information to the agent's long-term memory store.",
            SaveMemoryParams.class,
            true,  // isMutating
            false  // requiresApproval (saves are automatic memory updates)
        );

        toolRegistry.registerTool(
            "search_memory",
            "Searches the agent's long-term memory store for previously saved facts matching the query.",
            SearchMemoryParams.class
        );
    }

    @Override
    public boolean supports(String toolName) {
        return "save_memory".equals(toolName) || "search_memory".equals(toolName);
    }

    @Override
    public String execute(String toolName, String jsonArgs) {
        log.info("EXECUTE: Running memory tool [{}] with args: {}", toolName, jsonArgs);
        try {
            if ("save_memory".equals(toolName)) {
                SaveMemoryParams params = objectMapper.readValue(jsonArgs, SaveMemoryParams.class);
                if (params.text() == null || params.text().isBlank()) {
                    return "{\"status\": \"error\", \"message\": \"text parameter is empty\"}";
                }
                
                String id = UUID.randomUUID().toString();
                LongTermMemory memory = new LongTermMemory(id, params.text());
                AddLongTermMemoryRequest request = new AddLongTermMemoryRequest(List.of(memory));
                
                restClient.addLongTermMemories(request);
                return "{\"status\": \"success\", \"message\": \"Memory saved successfully\", \"memoryId\": \"" + id + "\"}";
            }

            if ("search_memory".equals(toolName)) {
                SearchMemoryParams params = objectMapper.readValue(jsonArgs, SearchMemoryParams.class);
                if (params.query() == null || params.query().isBlank()) {
                    return "{\"status\": \"error\", \"message\": \"query parameter is empty\"}";
                }

                SearchRequest request = new SearchRequest(params.query());
                SearchResponse response = restClient.searchLongTermMemory(request);
                
                if (response == null || response.results() == null) {
                    return "{\"status\": \"success\", \"results\": []}";
                }
                
                return objectMapper.writeValueAsString(response);
            }
        } catch (Exception e) {
            log.error("Error executing memory tool {}", toolName, e);
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
        return "{\"status\": \"error\", \"message\": \"Unsupported tool\"}";
    }
}
