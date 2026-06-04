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
    private final ChatClient fallbackClient;
    private final ToolRegistry toolRegistry;

    public SpringAiLlmProvider(ChatClient chatClient, ToolRegistry toolRegistry) {
        this(chatClient, null, toolRegistry);
    }

    public SpringAiLlmProvider(ChatClient chatClient, ChatClient fallbackClient, ToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.fallbackClient = fallbackClient;
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

        String systemPrompt = systemPromptTemplate
                .replace("{task}", currentStep.description())
                .replace("{tools}", schemasStr.toString())
                .replace("{observations}", context.getObservations().toString());

        int retryCount = 0;
        int maxRetries = 2;
        String lastErrorMsg = null;
        String malformedJson = null;
        String rawContent = null;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        while (retryCount <= maxRetries) {
            try {
                ChatClient clientToUse = chatClient;
                if (retryCount > 0 && fallbackClient != null && retryCount == maxRetries) {
                    log.info("Self-correction failed. Falling back to planning model client.");
                    clientToUse = fallbackClient;
                }

                String userPrompt = "What is your next move?";
                if (retryCount > 0) {
                    userPrompt = String.format(
                        "Your previous response was malformed JSON and could not be parsed: %s\n" +
                        "Raw response received was:\n%s\n" +
                        "Please correct the JSON formatting. Ensure it strictly matches the schema and is properly escaped.",
                        lastErrorMsg, malformedJson
                    );
                }

                rawContent = clientToUse.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();

                if (rawContent == null || rawContent.isBlank()) {
                    throw new RuntimeException("LLM returned null or empty content.");
                }

                LlmDecision decision = mapper.readValue(rawContent, LlmDecision.class);

                if (decision == null || decision.type() == null) {
                    throw new RuntimeException("Parsed decision or decision type is null.");
                }

                return switch (decision.type().toUpperCase()) {
                    case "ACTION" -> {
                        if (decision.toolName() == null) {
                            throw new RuntimeException("ACTION decision but toolName was null.");
                        }
                        yield new ReasoningResult.Action(decision.toolName(), decision.jsonArgs() != null ? decision.jsonArgs() : "{}");
                    }
                    case "REPLAN" -> new ReasoningResult.Replan(decision.reason() != null ? decision.reason() : "No reason provided.");
                    case "FINAL_ANSWER" -> new ReasoningResult.FinalAnswer(decision.text() != null ? decision.text() : "");
                    default -> throw new RuntimeException("Unknown decision type: " + decision.type());
                };
            } catch (Exception e) {
                lastErrorMsg = e.getMessage();
                log.warn("LlmProvider think attempt {} failed: {}", retryCount, lastErrorMsg);
                malformedJson = (rawContent != null) ? rawContent : "No response string available (HTTP/API failure).";
                retryCount++;
            }
        }

        return new ReasoningResult.Error("Failed to parse LLM structured decision after " + maxRetries + " retries. Last error: " + lastErrorMsg);
    }
}