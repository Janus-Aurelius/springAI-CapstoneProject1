package com.springagentic.springaiagent.framework.registry.dto;

import java.util.List;
import java.util.Map;

public class RedisAgentMemoryDtos {

    public record ContentItem(String text) {}

    public record AddEventRequest(
        String sessionId,
        String actorId,
        String role,
        List<ContentItem> content,
        long createdAt
    ) {}

    public record SessionEvent(
        String role,
        List<ContentItem> content,
        long createdAt,
        String actorId
    ) {}

    public record SessionResponse(
        String sessionId,
        List<SessionEvent> events
    ) {}

    public record LongTermMemory(
        String id,
        String text,
        @com.fasterxml.jackson.annotation.JsonProperty("OwnerID") String ownerId
    ) {}

    public record AddLongTermMemoryRequest(List<LongTermMemory> memories) {}

    public record SearchRequest(String text) {}

    public record SearchResponse(List<LongTermMemory> results) {}
}
