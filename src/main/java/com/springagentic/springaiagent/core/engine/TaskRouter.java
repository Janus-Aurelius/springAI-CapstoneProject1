package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.spi.LlmProvider;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.springagentic.springaiagent.core.domain.AgentContext;

public class TaskRouter {

    private static final Logger log = LoggerFactory.getLogger(TaskRouter.class);
    private final LlmProvider llmProvider;
    private final ExecutionEngine executionEngine;

    public TaskRouter(LlmProvider llmProvider, ExecutionEngine executionEngine) {
        this.llmProvider = llmProvider;
        this.executionEngine = executionEngine;
    }

    public AgentContext routeAndExecute(String threadId, String userGoal, AgentDefinition agentDef) {
        // 1. Fast, cheap LLM call to categorize the task
        String prompt = """
            Analyze the following user goal.
            
            Categorize this task into one of the following categories:
            - SIMPLE: Answering general knowledge questions, simple translations, text summaries, formatting requests, greetings, jokes, conversational banter, or constraints on word count/style where no external tools, database queries, files, slack/github APIs, or web searches are required.
            - COMPLEX: Tasks that require looking up user information, database queries, web search, running code, using Puppeteer, sending Slack messages, updating Notion pages, or accessing external APIs.
            
            Reply with exactly ONE word: SIMPLE or COMPLEX. Do not include any punctuation, quotes, markdown backticks, or reasoning explanation.
            
            Goal: %s
            """.formatted(userGoal);

        // We use a dummy class 'String.class' here, assuming our provider handles simple text returns
        String rawRoute = llmProvider.structuredRequest("You are a router that categorizes user requests.", prompt, String.class);
        String route = rawRoute != null ? rawRoute.trim() : "";
        
        log.info("ROUTER: Raw response from LLM: [{}]", route);
        
        // Strip reasoning thoughts if any got through
        if (route.contains("<think>")) {
            int endThink = route.indexOf("</think>");
            if (endThink != -1) {
                route = route.substring(endThink + 8).trim();
            } else {
                int startThink = route.indexOf("<think>");
                route = route.substring(startThink + 7).trim();
            }
        }
        
        // Clean non-alphabetic characters (removing quotes, periods, backticks, spaces, etc.)
        route = route.replaceAll("[^a-zA-Z]", "");

        // Check if the cleaned response is SIMPLE, or fallback to check if it contains SIMPLE and not COMPLEX
        boolean isSimple = "SIMPLE".equalsIgnoreCase(route) || 
                          (!route.toUpperCase().contains("COMPLEX") && route.toUpperCase().contains("SIMPLE"));

        if (isSimple) {
            log.info("ROUTER: Fast-tracking simple task based on routed category [{}]...", route);
            String result = llmProvider.structuredRequest(agentDef.systemPrompt(), userGoal, String.class);
            AgentContext context = new AgentContext(threadId, userGoal);
            context.terminate("SUCCESS", result);
            return context;
        } else {
            log.info("ROUTER: Booting up Execution Engine for complex task based on routed category [{}]...", route);
            return executionEngine.runComplexTask(threadId, userGoal, agentDef);
        }
    }
}