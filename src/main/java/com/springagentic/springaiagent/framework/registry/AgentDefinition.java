package com.springagentic.springaiagent.framework.registry;

import java.util.List;

public record AgentDefinition(
        String agentId,
        String systemPrompt,
        List<String> allowedToolNames,
        double temperature
) {
}
