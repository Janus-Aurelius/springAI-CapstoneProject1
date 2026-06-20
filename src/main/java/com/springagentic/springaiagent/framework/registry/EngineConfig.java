package com.springagentic.springaiagent.framework.registry;

import com.springagentic.springaiagent.core.engine.ExecutionEngine;
import com.springagentic.springaiagent.core.engine.Planner;
import com.springagentic.springaiagent.core.engine.Reasoner;
import com.springagentic.springaiagent.core.engine.TaskRouter;
import com.springagentic.springaiagent.core.spi.LlmProvider;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import com.springagentic.springaiagent.core.spi.ToolExecutor;
import com.springagentic.springaiagent.core.domain.ObservationTruncator;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import com.springagentic.springaiagent.framework.config.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    // 1. Create Planner
    @Bean
    public Planner planner(LlmProvider llmProvider) {
        return new Planner(llmProvider);
    }

    // 2. Create Reasoner
    @Bean
    public Reasoner reasoner(LlmProvider llmProvider) {
        return new Reasoner(llmProvider);
    }

    // 3. Create Agent Evaluator
    @Bean
    public com.springagentic.springaiagent.core.engine.AgentEvaluator agentEvaluator(
            LlmProvider llmProvider,
            io.opentelemetry.api.OpenTelemetry openTelemetry,
            com.springagentic.springaiagent.adapters.memory.AgentEvaluationRepository repository,
            LlmProperties llmProperties) {
        return new com.springagentic.springaiagent.core.engine.AgentEvaluator(
                llmProvider,
                openTelemetry.getTracer("spring-ai-agent"),
                repository,
                llmProperties
        );
    }

    // 4. Create Execution Engine (The Core Loop)
    @Bean
    public ExecutionEngine executionEngine(Planner planner, Reasoner reasoner,
                                           ToolExecutor toolExecutor, MemoryStore memoryStore,
                                           ObservationTruncator observationTruncator,
                                           ToolRegistry toolRegistry,
                                           com.springagentic.springaiagent.core.sandbox.McpContainerFactory containerFactory,
                                           com.springagentic.springaiagent.core.security.SecretRedactor secretRedactor,
                                           LlmProperties llmProperties,
                                           io.opentelemetry.api.OpenTelemetry openTelemetry,
                                           com.springagentic.springaiagent.core.engine.AgentEvaluator agentEvaluator) {
        return new ExecutionEngine(planner, reasoner, toolExecutor, memoryStore, observationTruncator, toolRegistry, containerFactory, secretRedactor, llmProperties, openTelemetry.getTracer("spring-ai-agent"), agentEvaluator);
    }

    // 4. Create the Task Router (The Front Door)
    @Bean
    public TaskRouter taskRouter(LlmProvider llmProvider, ExecutionEngine executionEngine) {
        return new TaskRouter(llmProvider, executionEngine);
    }
}
