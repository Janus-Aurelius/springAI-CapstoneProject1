package com.springagentic.springaiagent.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
    GuardrailProperties guardrails,
    List<ProviderConfig> providers,
    boolean stripReasoning,
    Map<String, ModelPriceConfig> pricing
) {
    public LlmProperties {
        if (pricing == null) {
            pricing = java.util.Collections.emptyMap();
        }
    }

    public record ModelPriceConfig(
        double inputCostPerMillion,
        double outputCostPerMillion
    ) {}
    public record GuardrailProperties(
        int maxActions,
        int maxReplans,
        int stagnationThreshold,
        long maxTokenBudget,
        int maxObservationTokens,
        List<String> customRules,
        boolean selfCorrectionEnabled
    ) {
        public GuardrailProperties {
            if (customRules == null) {
                customRules = java.util.Collections.emptyList();
            }
        }
    }

    public record ProviderConfig(
        String id,
        String baseUrl,
        String apiKey,
        boolean isPrimary,
        int maxContextWindow,
        Map<String, String> models // keys: planner, reasoner
    ) {}
}
