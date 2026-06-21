package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);
    private final LlmProperties properties;
    private final ObservationRegistry observationRegistry;
    private final Map<String, OpenAiChatModel> materializedClients = new ConcurrentHashMap<>();
    private final Map<String, LlmProperties.ProviderConfig> configs = new ConcurrentHashMap<>();

    public LlmProviderRegistry(LlmProperties properties, ObservationRegistry observationRegistry) {
        this.properties = properties;
        this.observationRegistry = observationRegistry;
        if (properties.providers() != null) {
            for (LlmProperties.ProviderConfig config : properties.providers()) {
                log.info("Registering LLM Provider: {} with base URL: {}", config.id(), config.baseUrl());
                if (config.models() != null) {
                    log.info("  Models: {}", config.models());
                }
                configs.put(config.id(), config);
            }
        } else {
            log.warn("WARNING: No LLM providers found in properties!");
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
        log.info("Creating OpenAI client for [{}] at [{}] with key hint: {}", config.id(), config.baseUrl(), keyHint);
        
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .timeout(Duration.ofSeconds(90))
                .build();

        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .timeout(Duration.ofSeconds(90))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(options)
                .observationRegistry(observationRegistry)
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
