package com.springagentic.springaiagent.core.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AgentContextJacksonTest {

    @Test
    public void testSerializationDeserialization() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        AgentContext context = new AgentContext("thread-123", "Verify Jackson");
        context.addObservation("test-tool", "success");

        String json = objectMapper.writeValueAsString(context);
        System.out.println("Serialized JSON: " + json);

        assertTrue(json.contains("\"observations\""));

        AgentContext deserialized = objectMapper.readValue(json, AgentContext.class);
        assertNotNull(deserialized.getObservationsList());
        assertEquals(1, deserialized.getObservationsList().size());
        assertEquals("Action [test-tool] Result: success", deserialized.getObservationsList().get(0));
    }
}
