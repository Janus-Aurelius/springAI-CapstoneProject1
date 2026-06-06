package com.springagentic.springaiagent.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
    GuardrailProperties guardrails,
    List<ProviderConfig> providers,
    boolean stripReasoning
) {
    public record GuardrailProperties(
        int maxActions,
        int maxReplans,
        int stagnationThreshold,
        long maxTokenBudget,
        int maxObservationTokens
    ) {}

    public record ProviderConfig(
        String id,
        String baseUrl,
        String apiKey,
        boolean isPrimary,
        int maxContextWindow,
        Map<String, String> models // keys: planner, reasoner
    ) {}
}
