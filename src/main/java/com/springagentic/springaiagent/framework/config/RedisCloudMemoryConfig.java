package com.springagentic.springaiagent.framework.config;

import com.springagentic.springaiagent.adapters.memory.RedisAgentMemoryRestClient;
import com.springagentic.springaiagent.adapters.memory.RedisCloudAgentChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(RedisAgentMemoryProperties.class)
public class RedisCloudMemoryConfig {

    @Bean
    public ChatMemory chatMemory(RedisAgentMemoryRestClient restClient) {
        return new RedisCloudAgentChatMemory(restClient);
    }
    
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
