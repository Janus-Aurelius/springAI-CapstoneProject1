package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ResilientLlmRouter implements LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmRouter.class);

    private final LlmProviderRegistry registry;
    private final ContextManager contextManager;
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    public ResilientLlmRouter(LlmProviderRegistry registry, ContextManager contextManager) {
        this.registry = registry;
        this.contextManager = contextManager;
    }

    @Override
    public ChatResponse generate(List<Message> messages, TaskType taskType) {
        List<LlmProperties.ProviderConfig> providers = registry.getAllConfigs();
        
        Exception lastException = null;

        for (LlmProperties.ProviderConfig config : providers) {
            if (isCooldowned(config.id())) {
                log.warn("Skipping cooldowned provider: {}", config.id());
                continue;
            }

            try {
                log.info("Attempting generation with provider: {}", config.id());
                
                // 1. Truncate context for this provider
                List<Message> truncatedMessages = contextManager.truncate(messages, config.maxContextWindow());
                
                // 2. Resolve model name for the task
                String modelName = config.models().get(taskType.name().toLowerCase());
                if (modelName == null) {
                    throw new IllegalStateException("Model not configured for task " + taskType + " in provider " + config.id());
                }

                // 3. Prepare options
                OpenAiChatOptions options = OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.2)
                        .maxTokens(4096)
                        .build();

                // 4. Call LLM with Reactive TTFT Monitoring
                OpenAiChatModel client = registry.getClient(config.id());
                
                ChatResponse response;
                try {
                    // We try stream() first to monitor TTFT (Time-To-First-Token) and support surefire tests
                    Flux<ChatResponse> responseFlux = client.stream(new Prompt(truncatedMessages, options));
                    
                    // Apply 30s timeout to allow slower providers under load or tool-calling setups to respond
                    List<ChatResponse> chunks = responseFlux
                            .timeout(Duration.ofSeconds(30)) 
                            .collectList()
                            .block(Duration.ofMinutes(2));

                    if (chunks == null || chunks.isEmpty()) {
                        throw new RuntimeException("Empty response stream from provider " + config.id());
                    }

                    // Aggregate chunks back into a single ChatResponse
                    response = aggregateChunks(chunks);
                } catch (com.openai.errors.OpenAIInvalidDataException streamEx) {
                    log.warn("Streaming chunk deserialization failed for provider {} (likely missing 'id'). Falling back to non-streaming call. Error: {}", config.id(), streamEx.getMessage());
                    // Fall back to synchronous call to bypass streaming chunk schema incompatibility issues (e.g. missing 'id')
                    response = client.call(new Prompt(truncatedMessages, options));
                    if (response == null) {
                        throw new RuntimeException("Empty response from provider " + config.id());
                    }
                }
                return response;

            } catch (Exception e) {
                log.error("Provider {} failed: {}", config.id(), e.getMessage());
                lastException = e;
                
                if (shouldCooldown(e)) {
                    cooldownMap.put(config.id(), System.currentTimeMillis() + 30000); // 30s cooldown
                }
            }
        }

        throw new RuntimeException("All LLM providers exhausted. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"), lastException);
    }

    private ChatResponse aggregateChunks(List<ChatResponse> chunks) {
        if (chunks.size() == 1) return chunks.get(0);
        
        StringBuilder contentBuilder = new StringBuilder();
        // Just a simple aggregation for now. For tool calls, we'd need more logic.
        for (ChatResponse chunk : chunks) {
            if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
                String text = chunk.getResult().getOutput().getText();
                if (text != null) {
                    contentBuilder.append(text);
                }
            }
        }
        
        // Return a new ChatResponse with aggregated content
        // Note: usage metadata might be only in the last chunk
        ChatResponse lastChunk = chunks.get(chunks.size() - 1);
        AssistantMessage aggregatedMessage = new AssistantMessage(contentBuilder.toString());
        Generation generation = new Generation(aggregatedMessage);
        
        return new ChatResponse(List.of(generation), lastChunk.getMetadata());
    }

    private boolean isCooldowned(String id) {
        Long expiration = cooldownMap.get(id);
        if (expiration == null) return false;
        if (System.currentTimeMillis() > expiration) {
            cooldownMap.remove(id);
            return false;
        }
        return true;
    }

    private boolean shouldCooldown(Exception e) {
        String msg = e.getMessage().toLowerCase();
        return msg.contains("429") || msg.contains("too many requests") || 
               msg.contains("500") || msg.contains("503") || 
               msg.contains("timeout") || msg.contains("connection refused") ||
               msg.contains("exhausted");
    }
}
