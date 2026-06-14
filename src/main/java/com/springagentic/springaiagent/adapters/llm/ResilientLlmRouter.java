package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.framework.config.LlmProperties;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ResilientLlmRouter implements LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmRouter.class);

    private final LlmProviderRegistry registry;
    private final ContextManager contextManager;
    private final Retry retry;

    public ResilientLlmRouter(LlmProviderRegistry registry, ContextManager contextManager, RetryRegistry retryRegistry) {
        this.registry = registry;
        this.contextManager = contextManager;
        this.retry = retryRegistry.retry("llmProvider");
    }

    @Override
    public ChatResponse generate(List<Message> messages, TaskType taskType) {
        List<LlmProperties.ProviderConfig> providers = registry.getAllConfigs();
        if (taskType == TaskType.REASONER) {
            providers = providers.stream()
                    .filter(config -> !config.id().startsWith("project-a"))
                    .collect(Collectors.toList());
        }
        
        Exception lastException = null;

        for (LlmProperties.ProviderConfig config : providers) {
            try {
                return Retry.decorateCheckedSupplier(retry, () -> {
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

                    // 4. Call LLM
                    OpenAiChatModel client = registry.getClient(config.id());
                    
                    try {
                        Flux<ChatResponse> responseFlux = client.stream(new Prompt(truncatedMessages, options));
                        
                        List<ChatResponse> chunks = responseFlux
                                .timeout(Duration.ofSeconds(30)) 
                                .collectList()
                                .block(Duration.ofMinutes(2));

                        if (chunks == null || chunks.isEmpty()) {
                            throw new RuntimeException("Empty response stream from provider " + config.id());
                        }

                        return aggregateChunks(chunks);
                    } catch (com.openai.errors.OpenAIInvalidDataException streamEx) {
                        log.warn("Streaming chunk deserialization failed for provider {}. Falling back to non-streaming call.", config.id());
                        ChatResponse response = client.call(new Prompt(truncatedMessages, options));
                        if (response == null) {
                            throw new RuntimeException("Empty response from provider " + config.id());
                        }
                        return response;
                    }
                }).get();

            } catch (Throwable e) {
                log.error("Provider {} failed after retries: {}", config.id(), e.getMessage());
                lastException = (e instanceof Exception) ? (Exception) e : new RuntimeException(e);
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
}
