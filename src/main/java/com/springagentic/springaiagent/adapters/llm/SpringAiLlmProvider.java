package com.springagentic.springaiagent.adapters.llm;

import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.domain.ReasoningResult;
import com.springagentic.springaiagent.core.domain.Step;
import com.springagentic.springaiagent.core.domain.ToolSchema;
import com.springagentic.springaiagent.core.spi.LlmProvider;
import com.springagentic.springaiagent.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class SpringAiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmProvider.class);

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;

    public SpringAiLlmProvider(ChatClient chatClient, ToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public <T> T structuredRequest(String systemPrompt, String userPrompt, Class<T> returnType) {
        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(returnType);
        } catch (Exception e) {
            log.error("Failed to process structuredRequest for type: {}", returnType.getSimpleName(), e);
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ReasoningResult think(AgentContext context, Step currentStep, List<String> allowedTools) {

        // We define a DTO specifically for parsing the LLM's raw JSON output
        record LlmDecision(String type, String toolName, String jsonArgs, String reason, String text) {}

        // 1. Resolve full JSON schemas for the allowed tools
        Collection<ToolSchema> schemas = toolRegistry.getSchemas(allowedTools);
        StringBuilder schemasStr = new StringBuilder();
        for (ToolSchema schema : schemas) {
            schemasStr.append("Tool Name: ").append(schema.name()).append("\n")
                      .append("Description: ").append(schema.description()).append("\n")
                      .append("Parameters Schema: ").append(schema.jsonSchemaParameters()).append("\n\n");
        }

        // 2. Define prompt templates for secure rendering
        String systemPromptTemplate = """
            You are an AI Reasoner. Your task is: {task}
            
            Available tools (with strict schemas):
            {tools}
            
            Past Observations:
            {observations}
            
            You must decide what to do next. Output JSON matching exactly ONE of these types:
            1. ACTION: {"type": "ACTION", "toolName": "name_of_tool", "jsonArgs": "{\\"arg\\": \\"val\\"}"}
            2. REPLAN: {"type": "REPLAN", "reason": "why we need a new plan"}
            3. FINAL_ANSWER: {"type": "FINAL_ANSWER", "text": "the final deduction"}
            """;

        PromptTemplate template = new PromptTemplate(systemPromptTemplate);
        String systemPrompt = template.render(Map.of(
            "task", currentStep.description(),
            "tools", schemasStr.toString(),
            "observations", context.getObservations().toString()
        ));

        // 3. Request execution and handle errors gracefully
        try {
            LlmDecision decision = chatClient.prompt()
                    .system(systemPrompt)
                    .user("What is your next move?")
                    .call()
                    .entity(LlmDecision.class);

            if (decision == null || decision.type() == null) {
                return new ReasoningResult.Error("LLM returned a null or empty decision type.");
            }

            return switch (decision.type().toUpperCase()) {
                case "ACTION" -> {
                    if (decision.toolName() == null) {
                        yield new ReasoningResult.Error("LLM returned ACTION decision type but toolName was null.");
                    }
                    yield new ReasoningResult.Action(decision.toolName(), decision.jsonArgs() != null ? decision.jsonArgs() : "{}");
                }
                case "REPLAN" -> new ReasoningResult.Replan(decision.reason() != null ? decision.reason() : "No reason provided.");
                case "FINAL_ANSWER" -> new ReasoningResult.FinalAnswer(decision.text() != null ? decision.text() : "");
                default -> new ReasoningResult.Error("LLM returned unknown decision type: " + decision.type());
            };
        } catch (Exception e) {
            log.error("Failed to communicate with LLM or parse structured decision DTO", e);
            return new ReasoningResult.Error("LLM communication or parsing failure: " + e.getMessage());
        }
    }
}