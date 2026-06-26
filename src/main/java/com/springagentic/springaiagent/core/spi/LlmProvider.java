package com.springagentic.springaiagent.core.spi;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;

import java.util.List;

public interface LlmProvider {
    // For the Planner to get JSON
    <T> T structuredRequest(String systemPrompt, String userPrompt, Class<T> returnType);

    // Overloaded to pass active AgentContext explicitly
    <T> T structuredRequest(AgentContext context, String systemPrompt, String userPrompt, Class<T> returnType);

    // For the Reasoner to get Tool Actions or Final Answers
    ReasoningResult think(AgentContext context, Step currentStep, List<String> allowedTools);
}
