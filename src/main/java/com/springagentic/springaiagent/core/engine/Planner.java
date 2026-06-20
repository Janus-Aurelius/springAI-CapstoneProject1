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
            
            Available contextual tools for information gathering:
            - tavily_search: Perform a web search to gather up-to-date information.
            - get_file_contents: Read the contents of a file from a repository.
            - list_issues: List issues in a GitHub repository.
            - query: Execute a SQL query on the isolated database.
            
            You may schedule steps that utilize these tools to gather context before performing actions.
            
            IMPORTANT: Output ONLY the final Plan JSON structure. Do NOT write any discussion, commentary, preamble, or conversational debate. Keep your step descriptions and plan simple.
            """;

        PromptTemplate template = new PromptTemplate(systemPromptTemplate);
        String systemPrompt = template.render(Map.of(
            "history", historyContext,
            "summaries", context.getStepSummaries().toString()
        ));

        PromptTemplate userTemplate = new PromptTemplate("Goal: {goal}");
        String userPrompt = userTemplate.render(Map.of("goal", context.getUserGoal()));

        try {
            org.slf4j.MDC.put("threadId", context.getThreadId());
            log.info("PLANNER: Generating execution plan...");
            Plan plan = llmProvider.structuredRequest(context, systemPrompt, userPrompt, Plan.class);

            if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
                log.warn("PLANNER: Received null or empty plan from LLM!");
            } else {
                log.info("PLANNER: Plan generated with {} step(s):", plan.steps().size());
                for (int i = 0; i < plan.steps().size(); i++) {
                    var step = plan.steps().get(i);
                    log.info("  Step [{}/{}] id='{}' deps={} | description='{}' | expectedOutcome='{}'",
                            i + 1, plan.steps().size(),
                            step.stepId(),
                            step.dependsOn() != null ? step.dependsOn() : "[]",
                            step.description(),
                            step.expectedOutcome());
                }
            }

            return plan;
        } finally {
            org.slf4j.MDC.remove("threadId");
        }

    }
}

