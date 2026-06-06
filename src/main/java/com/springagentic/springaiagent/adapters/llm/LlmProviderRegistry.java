package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LlmProviderRegistry {

    private final LlmProperties properties;
    private final Map<String, OpenAiChatModel> materializedClients = new ConcurrentHashMap<>();
    private final Map<String, LlmProperties.ProviderConfig> configs = new ConcurrentHashMap<>();

    public LlmProviderRegistry(LlmProperties properties) {
        this.properties = properties;
        for (LlmProperties.ProviderConfig config : properties.providers()) {
            configs.put(config.id(), config);
        }
    }

    public OpenAiChatModel getClient(String providerId) {
        return materializedClients.computeIfAbsent(providerId, id -> {
            LlmProperties.ProviderConfig config = configs.get(id);
            if (config == null) {
                throw new IllegalArgumentException("No provider configured with ID: " + id);
            }
            return createClient(config);
        });
    }

    private OpenAiChatModel createClient(LlmProperties.ProviderConfig config) {
        // Use the official OpenAI Java SDK client with explicit timeouts
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .timeout(Duration.ofSeconds(30)) // Total request timeout
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .build();
    }

    public List<LlmProperties.ProviderConfig> getAllConfigs() {
        return properties.providers();
    }

    public LlmProperties.ProviderConfig getPrimaryConfig() {
        return properties.providers().stream()
                .filter(LlmProperties.ProviderConfig::isPrimary)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary LLM provider configured."));
    }
}
