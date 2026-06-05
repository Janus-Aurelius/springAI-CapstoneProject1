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
    private final String modelName;
    private final String fallbackModelName;

    public SpringAiLlmProvider(ChatClient chatClient, String modelName, ToolRegistry toolRegistry) {
        this(chatClient, modelName, null, null, toolRegistry);
    }

    public SpringAiLlmProvider(ChatClient chatClient, String modelName, ChatClient fallbackClient, String fallbackModelName, ToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.modelName = modelName;
        this.fallbackClient = fallbackClient;
        this.fallbackModelName = fallbackModelName;
        this.toolRegistry = toolRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T structuredRequest(String systemPrompt, String userPrompt, Class<T> returnType) {
        try {
            if (returnType == String.class) {
                String content = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();
                if (content != null && content.contains("<think>")) {
                    int endThink = content.indexOf("</think>");
                    if (endThink != -1) {
                        content = content.substring(endThink + 8).trim();
                    } else {
                        int startThink = content.indexOf("<think>");
                        content = content.substring(startThink + 7).trim();
                    }
                }
                return (T) content;
            }

            org.springframework.ai.converter.BeanOutputConverter<T> converter = 
                    new org.springframework.ai.converter.BeanOutputConverter<>(returnType);

            org.springframework.ai.openai.OpenAiChatOptions.Builder options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .model(modelName)
                    .maxTokens(4096);

            String rawContent = chatClient.prompt()
                    .system(systemPrompt + "\n" + converter.getFormat())
                    .user(userPrompt + "\n\nIMPORTANT: Your response must consist ONLY of the raw JSON object matching the schema above. " +
                            "Do NOT include any conversational introduction, preamble, markdown code block backticks (no ```json), " +
                            "or explanatory text. Start your response directly with '{' and end with '}'.")
                    .options(options)
                    .call()
                    .content();

            if (rawContent == null || rawContent.isBlank()) {
                throw new RuntimeException("LLM returned null or empty content.");
            }

            String cleanJson = cleanAndExtractJson(rawContent);
            return converter.convert(cleanJson);
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

                String currentModel = (clientToUse == chatClient) ? modelName : fallbackModelName;
                org.springframework.ai.openai.OpenAiChatOptions.Builder options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(currentModel)
                        .maxTokens(4096);

                rawContent = clientToUse.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .options(options)
                        .call()
                        .content();

                String cleanJson = cleanAndExtractJson(rawContent);
                LlmDecision decision = mapper.readValue(cleanJson, LlmDecision.class);

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

    private String cleanAndExtractJson(String input) {
        if (input == null) {
            return null;
        }
        String processed = input.trim();
        
        // Remove <think>...</think> blocks
        if (processed.contains("<think>")) {
            int endThink = processed.indexOf("</think>");
            if (endThink != -1) {
                processed = processed.substring(endThink + 8).trim();
            } else {
                int startThink = processed.indexOf("<think>");
                processed = processed.substring(startThink + 7).trim();
            }
        }
        
        // Remove markdown code blocks
        if (processed.contains("```")) {
            int firstIdx = processed.indexOf("```");
            int startIdx = firstIdx + 3;
            String remainder = processed.substring(startIdx).trim();
            if (remainder.toLowerCase().startsWith("json")) {
                remainder = remainder.substring(4).trim();
            }
            
            int lastIdx = remainder.lastIndexOf("```");
            if (lastIdx != -1) {
                processed = remainder.substring(0, lastIdx).trim();
            } else {
                processed = remainder;
            }
        }
        
        // Extract the JSON object boundaries
        int firstBrace = processed.indexOf('{');
        int lastBrace = processed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            processed = processed.substring(firstBrace, lastBrace + 1).trim();
        }
        
        return processed;
    }
}