package com.springagentic.springaiagent.framework.registry;

import com.springagentic.springaiagent.core.engine.ExecutionEngine;
import com.springagentic.springaiagent.core.engine.Planner;
import com.springagentic.springaiagent.core.engine.Reasoner;
import com.springagentic.springaiagent.core.engine.TaskRouter;
import com.springagentic.springaiagent.core.spi.LlmProvider;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.domain.ObservationTruncator;
import com.springagentic.springaiagent.adapters.llm.SpringAiLlmProvider;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    // Define two LlmProvider beans with different models
    @Bean
    @Qualifier("planningLlm")
    public LlmProvider planningLlmProvider(ChatClient.Builder builder, ToolRegistry toolRegistry) {
        ChatClient client = builder
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o"))
                .build();
        return new SpringAiLlmProvider(client, toolRegistry);
    }

    @Bean
    @Qualifier("reasoningLlm")
    public LlmProvider reasoningLlmProvider(ChatClient.Builder builder, ToolRegistry toolRegistry) {
        ChatClient client = builder
                .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o-mini"))
                .build();
        return new SpringAiLlmProvider(client, toolRegistry);
    }

    // 1. Create Planner
    @Bean
    public Planner planner(@Qualifier("planningLlm") LlmProvider llmProvider) {
        return new Planner(llmProvider);
    }

    // 2. Create Reasoner
    @Bean
    public Reasoner reasoner(@Qualifier("reasoningLlm") LlmProvider llmProvider) {
        return new Reasoner(llmProvider);
    }

    // 3. Create Execution Engine (The Core Loop)
    @Bean
    public ExecutionEngine executionEngine(Planner planner, Reasoner reasoner,
                                           ToolExecutor toolExecutor, MemoryStore memoryStore,
                                           ObservationTruncator observationTruncator,
                                           ToolRegistry toolRegistry) {
        return new ExecutionEngine(planner, reasoner, toolExecutor, memoryStore, observationTruncator, toolRegistry);
    }

    // 4. Create the Task Router (The Front Door)
    @Bean
    public TaskRouter taskRouter(@Qualifier("planningLlm") LlmProvider llmProvider, ExecutionEngine executionEngine) {
        return new TaskRouter(llmProvider, executionEngine);
    }
}