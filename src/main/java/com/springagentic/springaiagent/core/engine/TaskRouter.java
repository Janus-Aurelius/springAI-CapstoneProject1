package com.springagentic.springaiagent.core.engine;

import com.springagentic.springaiagent.core.spi.LlmProvider;
import com.springagentic.springaiagent.framework.registry.AgentDefinition;

import com.springagentic.springaiagent.core.domain.AgentContext;

public class TaskRouter {

    private final LlmProvider llmProvider;
    private final ExecutionEngine executionEngine;

    public TaskRouter(LlmProvider llmProvider, ExecutionEngine executionEngine) {
        this.llmProvider = llmProvider;
        this.executionEngine = executionEngine;
    }

    public AgentContext routeAndExecute(String threadId, String userGoal, AgentDefinition agentDef) {
        // 1. Fast, cheap LLM call to categorize the task
        String prompt = """
            Analyze the following user goal. Is it a simple task (like translation, summarization, 
            or answering a general knowledge question) or a complex task requiring external tools and steps?
            Reply with exactly ONE word: SIMPLE or COMPLEX.
            
            Goal: %s
            """.formatted(userGoal);

        // We use a dummy class 'String.class' here, assuming our provider handles simple text returns
        String route = llmProvider.structuredRequest("You are a router.", prompt, String.class).trim();

        if ("SIMPLE".equalsIgnoreCase(route)) {
            System.out.println("ROUTER: Fast-tracking simple task...");
            String result = llmProvider.structuredRequest(agentDef.systemPrompt(), userGoal, String.class);
            AgentContext context = new AgentContext(threadId, userGoal);
            context.terminate("SUCCESS", result);
            return context;
        } else {
            System.out.println("ROUTER: Booting up Execution Engine for complex task...");
            return executionEngine.runComplexTask(threadId, userGoal, agentDef);
        }
    }
}