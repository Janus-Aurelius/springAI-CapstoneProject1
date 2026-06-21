package com.springagentic.springaiagent.core.engine;


import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.spi.LlmProvider;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Reasoner {

    private static final Logger log = LoggerFactory.getLogger(Reasoner.class);
    private static final Logger reasoningLog = LoggerFactory.getLogger("ReasoningTrace");
    private final LlmProvider llmProvider;

    public Reasoner(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public ReasoningResult think(AgentContext context, Step step, List<String> allowedTools) {
        String threadId = org.slf4j.MDC.get("threadId");
        reasoningLog.info("[{}] [{}] [REASONER_START] Analyzing step: {}", threadId, step.stepId(), step.description());
        log.info("REASONER: Analyzing step -> {}", step.description());
        return llmProvider.think(context, step, allowedTools);
    }
}
