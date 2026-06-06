package com.springagentic.springaiagent.core.engine;


import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.LlmProvider;

import java.util.List;

public class Reasoner {

    private final LlmProvider llmProvider;

    public Reasoner(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public ReasoningResult think(AgentContext context, Step step, List<String> allowedTools) {
        String threadId = org.slf4j.MDC.get("threadId");
        ReasoningTraceLogger.logTrace(threadId, step.stepId(), "REASONER_START", "Analyzing step: " + step.description());
        System.out.println("REASONER: Analyzing step -> " + step.description());
        return llmProvider.think(context, step, allowedTools);
    }
}