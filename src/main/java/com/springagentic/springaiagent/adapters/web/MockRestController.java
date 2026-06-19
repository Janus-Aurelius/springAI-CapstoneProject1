package com.springagentic.springaiagent.adapters.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api")
@Tag(name = "Mock API", description = "Mock API endpoints for agent tool-calling testing and REST simulations")
public class MockRestController {

    private static final List<MockResource> RESOURCES = new CopyOnWriteArrayList<>(List.of(
            new MockResource(1, "Task-Processor", "ACTIVE", "Processes background queues"),
            new MockResource(2, "Database-Sync", "IDLE", "Synchronizes read-replicas"),
            new MockResource(3, "Notification-Service", "ACTIVE", "Dispatches user alerts"),
            new MockResource(4, "Image-Optimizer", "MAINTENANCE", "Resizes and compresses uploads")
    ));

    @GetMapping("/health")
    @Operation(summary = "Check API Health", description = "Returns the status of the mock API to check if the server is healthy.")
    public HealthResponse checkHealth() {
        return new HealthResponse("healthy");
    }

    @GetMapping("/resources")
    @Operation(summary = "Get Resources", description = "Retrieves a list of all mock resources and their status details.")
    public List<MockResource> getResources() {
        return RESOURCES;
    }

    @PostMapping("/action")
    @Operation(summary = "Perform Action", description = "Executes an action on a specific mock resource, modifying its state, and returns a confirmation.")
    public ActionResponse performAction(@RequestBody ActionRequest request) {
        boolean found = false;
        for (int i = 0; i < RESOURCES.size(); i++) {
            MockResource res = RESOURCES.get(i);
            if (res.id() == request.resourceId()) {
                String newStatus = switch (request.action().toLowerCase()) {
                    case "stop" -> "STOPPED";
                    case "restart" -> "ACTIVE";
                    case "maintain" -> "MAINTENANCE";
                    case "update" -> "UPDATED";
                    default -> "ACTIVE";
                };
                RESOURCES.set(i, new MockResource(res.id(), res.name(), newStatus, res.description()));
                found = true;
                break;
            }
        }

        if (found) {
            return new ActionResponse(
                    true,
                    "Action '" + request.action() + "' executed successfully on resource " + request.resourceId(),
                    java.time.Instant.now().toString()
            );
        } else {
            return new ActionResponse(
                    false,
                    "Resource with ID " + request.resourceId() + " not found. No action taken.",
                    java.time.Instant.now().toString()
            );
        }
    }

    @Schema(description = "Mock health status response")
    public record HealthResponse(
            @Schema(description = "The overall health status of the application", example = "healthy")
            String status
    ) {}

    @Schema(description = "A mock resource representing a system entity")
    public record MockResource(
            @Schema(description = "The unique identifier of the resource", example = "1")
            int id,
            @Schema(description = "The name of the resource", example = "Task-Processor")
            String name,
            @Schema(description = "The current status of the resource", example = "ACTIVE")
            String status,
            @Schema(description = "The description of the resource", example = "Processes background queues")
            String description
    ) {}

    @Schema(description = "Request payload for performing an action on a resource")
    public record ActionRequest(
            @Schema(description = "The ID of the target resource", example = "1")
            int resourceId,
            @Schema(description = "The action to perform on the resource", example = "update")
            String action
    ) {}

    @Schema(description = "Response payload after performing an action")
    public record ActionResponse(
            @Schema(description = "Indicates whether the action was successful", example = "true")
            boolean success,
            @Schema(description = "A message summarizing the result of the action", example = "Action 'update' executed successfully on resource 1")
            String message,
            @Schema(description = "The timestamp of when the action was processed", example = "2026-06-19T16:50:00Z")
            String timestamp
    ) {}
}
