package com.springagentic.springaiagent.adapters.tools;

import com.springagentic.springaiagent.core.domain.ToolSchema;
import com.springagentic.springaiagent.core.sandbox.SandboxProfile;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JacksonToolRegistry implements ToolRegistry {

    private final Map<String, ToolDefinition> registry = new ConcurrentHashMap<>();

    private record ToolDefinition(String name, String description, Class<?> parameterClass, String jsonSchema, boolean isMutating, boolean requiresApproval, SandboxProfile profile) {}

    @Override
    public void registerTool(String name, String description, Class<?> parameterClass) {
        registerTool(name, description, parameterClass, false, false, SandboxProfile.COMPUTE);
    }

    @Override
    public void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating) {
        registerTool(name, description, parameterClass, isMutating, false, SandboxProfile.COMPUTE);
    }

    @Override
    public void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating, boolean requiresApproval) {
        registerTool(name, description, parameterClass, isMutating, requiresApproval, SandboxProfile.COMPUTE);
    }

    @Override
    public void registerTool(String name, String description, Class<?> parameterClass, boolean isMutating, boolean requiresApproval, SandboxProfile profile) {
        registry.put(name, new ToolDefinition(name, description, parameterClass, null, isMutating, requiresApproval, profile));
    }

    @Override
    public void registerTool(String name, String description, String jsonSchemaParameters, boolean isMutating, boolean requiresApproval, SandboxProfile profile) {
        registry.put(name, new ToolDefinition(name, description, null, jsonSchemaParameters, isMutating, requiresApproval, profile));
    }

    @Override
    public boolean isMutating(String toolName) {
        ToolDefinition def = registry.get(toolName);
        return def != null && def.isMutating();
    }

    @Override
    public boolean requiresApproval(String toolName) {
        ToolDefinition def = registry.get(toolName);
        return def != null && def.requiresApproval();
    }

    @Override
    public SandboxProfile getSandboxProfile(String toolName) {
        ToolDefinition def = registry.get(toolName);
        return def != null && def.profile() != null ? def.profile() : SandboxProfile.COMPUTE;
    }

    @Override
    public Collection<ToolSchema> getSchemas(Collection<String> toolNames) {
        Collection<ToolSchema> schemas = new ArrayList<>();
        if (toolNames != null) {
            for (String name : toolNames) {
                getSchema(name).ifPresent(schemas::add);
            }
        }
        return schemas;
    }

    @Override
    public Optional<ToolSchema> getSchema(String toolName) {
        ToolDefinition def = registry.get(toolName);
        if (def == null) {
            return Optional.empty();
        }
        
        if (def.jsonSchema() != null) {
            return Optional.of(new ToolSchema(def.name(), def.description(), def.jsonSchema()));
        }

        // Generate JSON schema from class using Spring AI's JsonSchemaGenerator
        String jsonSchema = org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForType(
            def.parameterClass(),
            new org.springframework.ai.util.json.schema.JsonSchemaGenerator.SchemaOption[0]
        );
        return Optional.of(new ToolSchema(def.name(), def.description(), jsonSchema));
    }
}
