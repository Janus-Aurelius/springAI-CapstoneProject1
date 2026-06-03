package com.springagentic.springaiagent.adapters.tools;

import com.springagentic.springaiagent.core.domain.ToolSchema;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

public class JacksonToolRegistryTest {

    private final JacksonToolRegistry registry = new JacksonToolRegistry();

    public record TestParams(
        @com.fasterxml.jackson.annotation.JsonPropertyDescription("The ID of the target user")
        String userId,

        @com.fasterxml.jackson.annotation.JsonPropertyDescription("User's access level")
        int accessLevel
    ) {}

    @Test
    public void testToolRegistrationAndSchemaGeneration() {
        registry.registerTool("test_tool", "A dummy test tool.", TestParams.class);

        Optional<ToolSchema> schemaOpt = registry.getSchema("test_tool");
        assertTrue(schemaOpt.isPresent());

        ToolSchema schema = schemaOpt.get();
        assertEquals("test_tool", schema.name());
        assertEquals("A dummy test tool.", schema.description());

        String jsonSchema = schema.jsonSchemaParameters();
        assertNotNull(jsonSchema);
        assertTrue(jsonSchema.contains("userId"));
        assertTrue(jsonSchema.contains("The ID of the target user"));
        assertTrue(jsonSchema.contains("accessLevel"));
        assertTrue(jsonSchema.contains("User's access level"));
    }
}
