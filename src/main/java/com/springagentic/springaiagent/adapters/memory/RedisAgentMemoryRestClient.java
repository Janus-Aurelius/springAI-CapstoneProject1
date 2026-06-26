//package com.springagentic.springaiagent.adapters.memory;
//
//import com.springagentic.springaiagent.framework.config.RedisAgentMemoryProperties;
//import com.springagentic.springaiagent.framework.registry.dto.RedisAgentMemoryDtos.*;
//import org.springframework.http.MediaType;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestClient;
//
//@Service
//public class RedisAgentMemoryRestClient {
//
//    private final RestClient restClient;
//    private final String storeId;
//
//    public RedisAgentMemoryRestClient(RedisAgentMemoryProperties properties, RestClient.Builder restClientBuilder) {
//        this.storeId = properties.storeId();
//        this.restClient = restClientBuilder
//                .baseUrl(properties.baseUrl())
//                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
//                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
//                .build();
//    }
//
//    public void addSessionEvent(AddEventRequest request) {
//        restClient.post()
//                .uri("/v1/stores/{storeId}/session-memory/events", storeId)
//                .body(request)
//                .retrieve()
//                .toBodilessEntity();
//    }
//
//    public SessionResponse getSessionMemory(String sessionId) {
//        return restClient.get()
//                .uri("/v1/stores/{storeId}/session-memory/{sessionId}", storeId, sessionId)
//                .retrieve()
//                .body(SessionResponse.class);
//    }
//
//    public void addLongTermMemories(AddLongTermMemoryRequest request) {
//        restClient.post()
//                .uri("/v1/stores/{storeId}/long-term-memory", storeId)
//                .body(request)
//                .retrieve()
//                .toBodilessEntity();
//    }
//
//    public SearchResponse searchLongTermMemory(SearchRequest request) {
//        return restClient.post()
//                .uri("/v1/stores/{storeId}/long-term-memory/search", storeId)
//                .body(request)
//                .retrieve()
//                .body(SearchResponse.class);
//    }
//}
