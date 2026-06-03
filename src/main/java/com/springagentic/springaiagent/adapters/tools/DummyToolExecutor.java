package com.springagentic.springaiagent.adapters.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DummyToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DummyToolExecutor.class);

    // Dynamic Parameter Schemas using Java Records and descriptions
    public record DatabaseQueryParams(
        @JsonPropertyDescription("The search query to match against database records")
        String query,

        @JsonPropertyDescription("Maximum number of rows to return (default is 10)")
        Integer limit,

        @JsonPropertyDescription("Offset count for pagination offsets")
        Integer offset
    ) {}

    public record CalculateMetricsParams(
        @JsonPropertyDescription("The metric name, e.g. daily_active_users, revenue")
        String metric,

        @JsonPropertyDescription("Start date of calculation in YYYY-MM-DD format")
        String startDate,

        @JsonPropertyDescription("End date of calculation in YYYY-MM-DD format")
        String endDate
    ) {}

    public DummyToolExecutor(ToolRegistry toolRegistry) {
        // Register schemas at runtime
        toolRegistry.registerTool(
            "search_database",
            "Searches the database with support for pagination and limit filtering.",
            DatabaseQueryParams.class
        );
        toolRegistry.registerTool(
            "calculate_metrics",
            "Calculates target metrics over a date range.",
            CalculateMetricsParams.class
        );
        toolRegistry.registerTool(
            "write_database",
            "Saves data changes to the database. Mutating. Requires human approval.",
            DatabaseQueryParams.class,
            true, // isMutating
            true  // requiresApproval
        );
    }

    @Override
    public boolean supports(String toolName) {
        return "search_database".equals(toolName) || "calculate_metrics".equals(toolName) || "write_database".equals(toolName);
    }

    @Override
    public String execute(String toolName, String jsonArgs) {
        log.info("EXECUTE: Running [{}] with args: {}", toolName, jsonArgs);

        // Simulate network delay
        try { 
            Thread.sleep(500); 
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt(); 
        }

        // Return dummy JSON result
        return """
            {
                "status": "success",
                "tool": "%s",
                "mock_data": "This is mock payload data. Verify parameters were read correctly."
            }
            """.formatted(toolName);
    }
}

