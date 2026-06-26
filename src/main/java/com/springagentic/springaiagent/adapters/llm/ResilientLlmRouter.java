package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.framework.config.LlmProperties;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ResilientLlmRouter implements LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmRouter.class);

    private final LlmProviderRegistry registry;
    private final ContextManager contextManager;
    private final Retry retry;
    private final MeterRegistry meterRegistry;
    private final LlmProperties llmProperties;

    public ResilientLlmRouter(LlmProviderRegistry registry, ContextManager contextManager,
                              RetryRegistry retryRegistry, MeterRegistry meterRegistry,
                              LlmProperties llmProperties) {
        this.registry = registry;
        this.contextManager = contextManager;
        this.retry = retryRegistry.retry("llmProvider");
        this.meterRegistry = meterRegistry;
        this.llmProperties = llmProperties;
    }

    @Override
    public ChatResponse generate(List<Message> messages, TaskType taskType, AgentContext context) {
        List<LlmProperties.ProviderConfig> providers = registry.getAllConfigs();
        if (taskType == TaskType.REASONER) {
            providers = providers.stream()
                    .filter(config -> !config.id().startsWith("project-a") && !config.id().startsWith("project-c"))
                    .collect(Collectors.toList());
        } else if (taskType == TaskType.JUDGE) {
            providers = providers.stream()
                    .filter(config -> config.id().startsWith("project-c"))
                    .collect(Collectors.toList());
        } else if (taskType == TaskType.PLANNER) {
            providers = providers.stream()
                    .filter(config -> !config.id().startsWith("project-c"))
                    .collect(Collectors.toList());
        }

        Exception lastException = null;

        for (LlmProperties.ProviderConfig config : providers) {
            try {
                long start = System.nanoTime();
                ChatResponse response = Retry.decorateCheckedSupplier(retry, () -> {
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
                        ChatResponse res = client.call(new Prompt(truncatedMessages, options));
                        if (res == null) {
                            throw new RuntimeException("Empty response from provider " + config.id());
                        }
                        return res;
                    } catch (Exception e) {
                        throw new RuntimeException("Call failed for provider " + config.id() + ": " + e.getMessage(), e);
                    }
                }).get();

                long durationNs = System.nanoTime() - start;
                String modelName = config.models().get(taskType.name().toLowerCase());

                if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    var usage = response.getMetadata().getUsage();
                    int inputTokens = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                    int outputTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                    int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : (inputTokens + outputTokens);

                    LlmProperties.ModelPriceConfig pricing = getPricing(modelName);
                    double inputCost = (inputTokens / 1_000_000.0) * pricing.inputCostPerMillion();
                    double outputCost = (outputTokens / 1_000_000.0) * pricing.outputCostPerMillion();
                    double totalCost = inputCost + outputCost;

                    meterRegistry.counter("llm.cost.usd", "model", modelName, "task_type", taskType.name()).increment(totalCost);
                    meterRegistry.counter("llm.tokens.input", "model", modelName, "task_type", taskType.name()).increment(inputTokens);
                    meterRegistry.counter("llm.tokens.output", "model", modelName, "task_type", taskType.name()).increment(outputTokens);
                    meterRegistry.counter("llm.tokens.total", "model", modelName, "task_type", taskType.name()).increment(totalTokens);
                    meterRegistry.timer("llm.latency", "model", modelName, "task_type", taskType.name()).record(Duration.ofNanos(durationNs));

                    if (context != null) {
                        context.addCost(totalCost);
                        context.addTokens(totalTokens);
                    }
                }

                return response;

            } catch (Throwable e) {
                log.error("Provider {} failed after retries: {}", config.id(), e.getMessage());
                lastException = (e instanceof Exception) ? (Exception) e : new RuntimeException(e);
            }
        }

        throw new RuntimeException("All LLM providers exhausted. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"), lastException);
    }

    private LlmProperties.ModelPriceConfig getPricing(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return llmProperties.pricing().getOrDefault("default", new LlmProperties.ModelPriceConfig(0.15, 0.60));
        }
        String lowerModel = modelName.toLowerCase();
        if (llmProperties.pricing().containsKey(lowerModel)) {
            return llmProperties.pricing().get(lowerModel);
        }
        String normalizedModelName = lowerModel.replaceAll("[^a-zA-Z0-9]", "");
        for (Map.Entry<String, LlmProperties.ModelPriceConfig> entry : llmProperties.pricing().entrySet()) {
            String normalizedKey = entry.getKey().toLowerCase().replaceAll("[^a-zA-Z0-9]", "");
            if (normalizedKey.contains(normalizedModelName) || normalizedModelName.contains(normalizedKey)) {
                return entry.getValue();
            }
        }
        if (lowerModel.contains("pro")) {
            return llmProperties.pricing().getOrDefault("gemini-1.5-pro", new LlmProperties.ModelPriceConfig(1.25, 5.00));
        } else if (lowerModel.contains("flash-8b")) {
            return llmProperties.pricing().getOrDefault("gemini-1.5-flash-8b", new LlmProperties.ModelPriceConfig(0.0375, 0.15));
        } else if (lowerModel.contains("flash")) {
            return llmProperties.pricing().getOrDefault("gemini-1.5-flash", new LlmProperties.ModelPriceConfig(0.075, 0.30));
        } else if (lowerModel.contains("gemma")) {
            return llmProperties.pricing().getOrDefault("gemma-2-9b", new LlmProperties.ModelPriceConfig(0.06, 0.24));
        }
        return llmProperties.pricing().getOrDefault("default", new LlmProperties.ModelPriceConfig(0.15, 0.60));
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
