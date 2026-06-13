package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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
        if (properties.providers() != null) {
            for (LlmProperties.ProviderConfig config : properties.providers()) {
                System.out.println("Registering LLM Provider: " + config.id() + " with base URL: " + config.baseUrl());
                if (config.models() != null) {
                    System.out.println("  Models: " + config.models());
                }
                configs.put(config.id(), config);
            }
        } else {
            System.err.println("WARNING: No LLM providers found in properties!");
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
        String key = config.apiKey();
        String keyHint = (key != null && key.length() > 5) ? key.substring(0, 5) + "..." : "null/short";
        System.out.println("Creating OpenAI client for [" + config.id() + "] at [" + config.baseUrl() + "] with key hint: " + keyHint);
        
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .timeout(Duration.ofSeconds(30))
                .build();

        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .timeout(Duration.ofSeconds(30))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(options)
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
