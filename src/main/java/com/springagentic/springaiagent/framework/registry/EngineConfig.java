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

    @org.springframework.beans.factory.annotation.Value("${app.llm.planning.model}")
    private String planningModel;

    @org.springframework.beans.factory.annotation.Value("${app.llm.reasoning.model}")
    private String reasoningModel;

    // Define two LlmProvider beans with different models
    @Bean
    @Qualifier("planningLlm")
    public LlmProvider planningLlmProvider(ChatClient.Builder builder, ToolRegistry toolRegistry) {
        ChatClient client = builder.clone()
                .defaultOptions(OpenAiChatOptions.builder().model(planningModel))
                .build();
        return new SpringAiLlmProvider(client, planningModel, toolRegistry);
    }

    @Bean
    @Qualifier("reasoningLlm")
    public LlmProvider reasoningLlmProvider(ChatClient.Builder builder, ToolRegistry toolRegistry) {
        ChatClient reasoningClient = builder.clone()
                .defaultOptions(OpenAiChatOptions.builder().model(reasoningModel))
                .build();
        ChatClient planningClient = builder.clone()
                .defaultOptions(OpenAiChatOptions.builder().model(planningModel))
                .build();
        return new SpringAiLlmProvider(reasoningClient, reasoningModel, planningClient, planningModel, toolRegistry);
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
                                           ToolRegistry toolRegistry,
                                           com.springagentic.springaiagent.core.sandbox.McpContainerFactory containerFactory,
                                           com.springagentic.springaiagent.core.security.SecretRedactor secretRedactor) {
        return new ExecutionEngine(planner, reasoner, toolExecutor, memoryStore, observationTruncator, toolRegistry, containerFactory, secretRedactor);
    }

    // 4. Create the Task Router (The Front Door)
    @Bean
    public TaskRouter taskRouter(@Qualifier("planningLlm") LlmProvider llmProvider, ExecutionEngine executionEngine) {
        return new TaskRouter(llmProvider, executionEngine);
    }
}