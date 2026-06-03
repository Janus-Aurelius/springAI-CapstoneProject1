package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.Plan;
import com.springagentic.springaiagent.core.spi.LlmProvider;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);
    private final LlmProvider llmProvider;

    public Planner(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    public Plan createPlan(AgentContext context, List<String> threadHistory) {
        String historyContext = threadHistory.isEmpty() ? "No prior turns." : String.join("\n", threadHistory);

        String systemPromptTemplate = """
            You are a master planner. Break down the user's goal into logical steps.
            If previous observations exist within this run, adjust the plan accordingly.
            
            Prior Conversation History (Do not repeat tasks already solved in these conclusions):
            {history}
            
            Distilled Step Summaries of Completed Steps so far:
            {summaries}
            """;

        PromptTemplate template = new PromptTemplate(systemPromptTemplate);
        String systemPrompt = template.render(Map.of(
            "history", historyContext,
            "summaries", context.getStepSummaries().toString()
        ));

        PromptTemplate userTemplate = new PromptTemplate("Goal: {goal}");
        String userPrompt = userTemplate.render(Map.of("goal", context.getUserGoal()));

        log.info("PLANNER: Generating execution plan...");
        return llmProvider.structuredRequest(systemPrompt, userPrompt, Plan.class);
    }
}

