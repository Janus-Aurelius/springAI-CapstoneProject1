package com.springagentic.springaiagent.adapters.memory;

import com.springagentic.springaiagent.framework.registry.dto.RedisAgentMemoryDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RedisCloudAgentChatMemoryTest {

    private RedisAgentMemoryRestClient mockRestClient;
    private ChatMemory chatMemory;

    @BeforeEach
    public void setUp() {
        mockRestClient = mock(RedisAgentMemoryRestClient.class);
        chatMemory = new RedisCloudAgentChatMemory(mockRestClient);
    }

    @Test
    public void testAddMessages() {
        String session = "session-456";
        List<Message> messages = Arrays.asList(
                new UserMessage("Hello agent"),
                new AssistantMessage("Hello user")
        );

        chatMemory.add(session, messages);

        ArgumentCaptor<AddEventRequest> captor = ArgumentCaptor.forClass(AddEventRequest.class);
        verify(mockRestClient, times(2)).addSessionEvent(captor.capture());

        List<AddEventRequest> requests = captor.getAllValues();
        assertEquals(2, requests.size());

        // First message: USER
        assertEquals(session, requests.get(0).sessionId());
        assertEquals("USER", requests.get(0).role());
        assertEquals("Hello agent", requests.get(0).content().get(0).text());

        // Second message: ASSISTANT
        assertEquals(session, requests.get(1).sessionId());
        assertEquals("ASSISTANT", requests.get(1).role());
        assertEquals("Hello user", requests.get(1).content().get(0).text());
    }

    @Test
    public void testGetMessages() {
        String session = "session-456";
        List<SessionEvent> events = Arrays.asList(
                new SessionEvent("USER", List.of(new ContentItem("Hi")), System.currentTimeMillis(), "user"),
                new SessionEvent("ASSISTANT", List.of(new ContentItem("How can I help you?")), System.currentTimeMillis(), "agent")
        );
        SessionResponse response = new SessionResponse(session, events);

        when(mockRestClient.getSessionMemory(session)).thenReturn(response);

        List<Message> result = chatMemory.get(session);

        assertNotNull(result);
        assertEquals(2, result.size());

        assertTrue(result.get(0) instanceof UserMessage);
        assertEquals("Hi", result.get(0).getText());

        assertTrue(result.get(1) instanceof AssistantMessage);
        assertEquals("How can I help you?", result.get(1).getText());
    }

    @Test
    public void testGetMessagesEmpty() {
        String session = "session-empty";
        when(mockRestClient.getSessionMemory(session)).thenReturn(null);

        List<Message> result = chatMemory.get(session);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
