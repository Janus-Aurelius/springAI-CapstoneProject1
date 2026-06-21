package com.springagentic.springaiagent.adapters.memory;

import com.springagentic.springaiagent.framework.registry.dto.RedisAgentMemoryDtos;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RedisCloudAgentChatMemory implements ChatMemory {

    private final RedisAgentMemoryRestClient restClient;

    public RedisCloudAgentChatMemory(RedisAgentMemoryRestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        for (Message message : messages) {
            String role = mapToRole(message.getMessageType());
            RedisAgentMemoryDtos.AddEventRequest request = new RedisAgentMemoryDtos.AddEventRequest(
                    conversationId,
                    "agent-user", // Default actor ID
                    role,
                    List.of(new RedisAgentMemoryDtos.ContentItem(message.getText())),
                    System.currentTimeMillis()
            );
            restClient.addSessionEvent(request);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        RedisAgentMemoryDtos.SessionResponse response = restClient.getSessionMemory(conversationId);
        if (response == null || response.events() == null) {
            return Collections.emptyList();
        }
        return response.events().stream()
                .map(this::mapToMessage)
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String conversationId) {
        // Redis Session Memory API doesn't have a direct "clear" for session-memory events 
        // in the provided snippets, but typically clearing involves deleting the session 
        // or we can implement it as a no-op if the cloud service handles TTL.
        // For now, we will log that clear is not directly supported via the session-memory endpoint snippets.
    }

    private String mapToRole(MessageType type) {
        return switch (type) {
            case SYSTEM -> "SYSTEM";
            case USER -> "USER";
            case ASSISTANT -> "ASSISTANT";
            default -> "USER";
        };
    }

    private Message mapToMessage(RedisAgentMemoryDtos.SessionEvent event) {
        String text = event.content().stream()
                .map(RedisAgentMemoryDtos.ContentItem::text)
                .collect(Collectors.joining(" "));
        
        return switch (event.role().toUpperCase()) {
            case "SYSTEM" -> new SystemMessage(text);
            case "ASSISTANT" -> new AssistantMessage(text);
            default -> new UserMessage(text);
        };
    }
}
