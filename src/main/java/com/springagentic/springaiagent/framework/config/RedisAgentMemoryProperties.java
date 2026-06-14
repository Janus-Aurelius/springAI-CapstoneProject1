package com.springagentic.springaiagent.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "redis.agent-memory")
public record RedisAgentMemoryProperties(
    String baseUrl,
    String apiKey,
    String storeId
) {}
