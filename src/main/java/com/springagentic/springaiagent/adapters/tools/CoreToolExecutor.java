package com.springagentic.springaiagent.adapters.tools;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springagentic.springaiagent.adapters.web.MockRestController;
import com.springagentic.springaiagent.core.sandbox.SandboxProfile;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CoreToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(CoreToolExecutor.class);

    private final MockRestController mockRestController;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    public record PythonSandboxParams(
        @JsonPropertyDescription("The python code script to run in the sandbox")
        String code
    ) {}

    public record NetworkFetchParams(
        @JsonPropertyDescription("The URL to fetch")
        String url
    ) {}

    public record IsolatedDatabaseParams(
        @JsonPropertyDescription("The SQL query to run against the isolated database")
        String query
    ) {}

    // Parameters for custom tools
    public record ListResourcesParams() {}

    public record ManageResourceParams(
        @JsonPropertyDescription("The ID of the target resource (e.g. 1, 2, 3, 4)")
        Integer resourceId,
        @JsonPropertyDescription("The action to perform on the resource (stop, restart, maintain, update)")
        String action
    ) {}

    public record SystemHealthParams() {}

    public CoreToolExecutor(ToolRegistry toolRegistry, MockRestController mockRestController) {
        this.mockRestController = mockRestController;

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

        // Register sandbox tools
        toolRegistry.registerTool(
            "execute_python_sandbox",
            "Executes Python code in a safe sandbox. Use this for math, computations, and data manipulation.",
            PythonSandboxParams.class,
            false,
            false,
            SandboxProfile.COMPUTE
        );
        toolRegistry.registerTool(
            "network_fetch_proxy",
            "Fetches content from a URL via a proxy.",
            NetworkFetchParams.class,
            false,
            false,
            SandboxProfile.FETCH
        );
        toolRegistry.registerTool(
            "query_isolated_database",
            "Queries the isolated PostgreSQL database.",
            IsolatedDatabaseParams.class,
            false,
            false,
            SandboxProfile.COMPUTE
        );

        // Register custom system management tools
        toolRegistry.registerTool(
            "list_system_resources",
            "Retrieves a list of all mock system resources and their current statuses.",
            ListResourcesParams.class
        );
        toolRegistry.registerTool(
            "manage_system_resource",
            "Executes an operational action on a mock system resource by ID. Mutating. Requires human approval.",
            ManageResourceParams.class,
            true,  // isMutating
            true   // requiresApproval
        );
        toolRegistry.registerTool(
            "get_system_health",
            "Checks the overall health status of the mock system.",
            SystemHealthParams.class
        );
    }

    @Override
    public boolean supports(String toolName) {
        // Only return true for tools we execute locally in this class.
        // Sandbox tools return false so they fall through to the ExecutionEngine's Docker-managed execution.
        return "search_database".equals(toolName) || 
               "calculate_metrics".equals(toolName) || 
               "write_database".equals(toolName) || 
               "search_archive".equals(toolName) || 
               "web_search".equals(toolName) ||
               "list_system_resources".equals(toolName) ||
               "manage_system_resource".equals(toolName) ||
               "get_system_health".equals(toolName);
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

        if ("list_system_resources".equals(toolName)) {
            try {
                var resources = mockRestController.getResources();
                return objectMapper.writeValueAsString(resources);
            } catch (Exception e) {
                return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
            }
        }

        if ("manage_system_resource".equals(toolName)) {
            try {
                ManageResourceParams params = objectMapper.readValue(jsonArgs, ManageResourceParams.class);
                var response = mockRestController.performAction(new MockRestController.ActionRequest(params.resourceId(), params.action()));
                return objectMapper.writeValueAsString(response);
            } catch (Exception e) {
                return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
            }
        }

        if ("get_system_health".equals(toolName)) {
            try {
                var health = mockRestController.checkHealth();
                return objectMapper.writeValueAsString(health);
            } catch (Exception e) {
                return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
            }
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

