package com.springagentic.springaiagent.core.spi;

import com.springagentic.springaiagent.core.domain.ToolSchema;
import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {
    void registerTool(String name, String description, Class<?> parameterClass);
    void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating);
    void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating, boolean requiresApproval);
    Collection<ToolSchema> getSchemas(Collection<String> toolNames);
    Optional<ToolSchema> getSchema(String toolName);
    boolean isMutating(String toolName);
    boolean requiresApproval(String toolName);
}
