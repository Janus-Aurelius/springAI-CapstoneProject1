package com.springagentic.springaiagent.core.spi;

import com.springagentic.springaiagent.core.domain.ToolSchema;
import com.springagentic.springaiagent.core.sandbox.SandboxProfile;
import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {
    void registerTool(String name, String description, Class<?> parameterClass);
    void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating);
    void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating, boolean requiresApproval);
    void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating, boolean requiresApproval, SandboxProfile profile);
    void registerTool(String name, String description, String jsonSchemaParameters, boolean isMutating, boolean requiresApproval, SandboxProfile profile);
    Collection<ToolSchema> getSchemas(Collection<String> toolNames);
    Optional<ToolSchema> getSchema(String toolName);
    boolean isMutating(String toolName);
    boolean requiresApproval(String toolName);
    SandboxProfile getSandboxProfile(String toolName);
}
