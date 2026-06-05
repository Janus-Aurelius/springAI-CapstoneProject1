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

    public record WebSearchParams(
        @JsonPropertyDescription("The web search query to search for information online")
        String query
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
        toolRegistry.registerTool(
            "search_archive",
            "Searches the historical archive database for users.",
            DatabaseQueryParams.class
        );
        toolRegistry.registerTool(
            "web_search",
            "Searches the web for up-to-date information.",
            WebSearchParams.class
        );
    }

    @Override
    public boolean supports(String toolName) {
        return "search_database".equals(toolName) || "calculate_metrics".equals(toolName) || "write_database".equals(toolName) || "search_archive".equals(toolName) || "web_search".equals(toolName);
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

        if ("search_database".equals(toolName)) {
            if (jsonArgs != null && jsonArgs.contains("Bob")) {
                return """
                    {
                        "status": "error",
                        "tool": "search_database",
                        "message": "User 'Bob' not found in active database."
                    }
                    """;
            }
            String mockData = "This is mock payload data. Verify parameters were read correctly.";
            if (jsonArgs != null && jsonArgs.contains("Alice")) {
                mockData = "User 'Alice' was found in the database. Account status: Active. ID: 88712.";
            }
            return """
                {
                    "status": "success",
                    "tool": "search_database",
                    "mock_data": "%s"
                }
                """.formatted(mockData);
        }

        if ("search_archive".equals(toolName)) {
            if (jsonArgs != null && jsonArgs.contains("Bob")) {
                return """
                    {
                        "status": "success",
                        "tool": "search_archive",
                        "mock_data": "User 'Bob' was found in historical archive database. Account status: Dormant. ID: 11223."
                    }
                    """;
            }
            return """
                {
                    "status": "success",
                    "tool": "search_archive",
                    "mock_data": "No matching archive record found."
                }
                """;
        }

        if ("calculate_metrics".equals(toolName)) {
            return """
                {
                    "status": "success",
                    "tool": "calculate_metrics",
                    "mock_data": "Target metrics calculated successfully. Active score: 92. Performance: Optimal."
                }
                """;
        }

        if ("web_search".equals(toolName)) {
            if (jsonArgs != null && jsonArgs.contains("Bob")) {
                return """
                    {
                        "status": "success",
                        "tool": "web_search",
                        "mock_data": "Search results for 'Bob': found profile on LinkedIn. Bob is a Senior Data Analyst at TechCorp. Email: bob@techcorp.com."
                    }
                    """;
            }
            return """
                {
                    "status": "success",
                    "tool": "web_search",
                    "mock_data": "No matching web search results found."
                }
                """;
        }

        // Return dummy JSON result
        return """
            {
                "status": "success",
                "tool": "%s",
                "mock_data": "Generic fallback execution."
            }
            """.formatted(toolName);
    }
}

