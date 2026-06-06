# Implementation Plan - Generic LLM Provider Integration (No Vendor Lock-In)

The goal is to revise the application to support **any OpenAI-compatible LLM provider** (such as Groq, DeepSeek, Together AI, OpenRouter, Perplexity, or local vLLM instances hosting Qwen/DeepSeek models) purely through environment variables in the `.env` file, without adding new Maven starters or hardcoding vendor-specific libraries.

Since almost all major LLM providers now support the OpenAI Chat Completions API format, we can parameterize the existing Spring AI OpenAI starter to talk to any compatible endpoint.

## Proposed Changes

### Configuration & Properties

#### [MODIFY] [application.properties](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/resources/application.properties)
We will expose the OpenAI client's base URL and API key, and configure the model names dynamically:
```properties
# Custom configuration for models
app.llm.planning.model=${PLANNING_MODEL:gpt-4o}
app.llm.reasoning.model=${REASONING_MODEL:gpt-4o-mini}

# Parameterize Spring AI OpenAI client
spring.ai.openai.api-key=${OPENAI_API_KEY:dummy-key}
spring.ai.openai.base-url=${OPENAI_BASE_URL:https://api.openai.com}
```

#### [MODIFY] [EngineConfig.java](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/src/main/java/com/springagentic/springaiagent/framework/registry/EngineConfig.java)
- Inject `planningModel` and `reasoningModel` values from properties.
- Remove hardcoded `"gpt-4o"` and `"gpt-4o-mini"` strings.
- Construct the `OpenAiChatOptions` options dynamically using the configured model names:
  ```java
  @Value("${app.llm.planning.model}")
  private String planningModel;

  @Value("${app.llm.reasoning.model}")
  private String reasoningModel;
  ```

---

### Environment & Docker Integration

#### [MODIFY] [.env.example](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/.env.example)
Add generic configurations at the top of the file with examples for both OpenAI and Groq/DeepSeek:
```properties
# =====================================================================
# LLM Provider Configuration (Supports OpenAI, Groq, DeepSeek, OpenRouter, etc.)
# =====================================================================

# 1. Base URL for the provider's OpenAI-compatible API
# - OpenAI: https://api.openai.com
# - Groq: https://api.groq.com/openai
# - DeepSeek: https://api.deepseek.com
OPENAI_BASE_URL=https://api.openai.com

# 2. Your API Key for the chosen provider
OPENAI_API_KEY=your_api_key_here

# 3. Model names to use for planning (smarter model) and reasoning (faster model)
# - OpenAI: gpt-4o / gpt-4o-mini
# - Groq: llama-3.1-70b-versatile / llama-3.1-8b-instant
# - DeepSeek: deepseek-chat
PLANNING_MODEL=gpt-4o
REASONING_MODEL=gpt-4o-mini
```

#### [MODIFY] [docker-compose.yml](file:///home/annguyen/master_projects/sem2_year3_projects/springaiagent/docker-compose.yml)
- Pass the generic environment variables into the container:
  - `SPRING_AI_OPENAI_API_KEY=${OPENAI_API_KEY}`
  - `SPRING_AI_OPENAI_BASE_URL=${OPENAI_BASE_URL}`
  - `PLANNING_MODEL=${PLANNING_MODEL}`
  - `REASONING_MODEL=${REASONING_MODEL}`

## Verification Plan

### Automated Tests
- Run `./mvnw clean compile` to ensure the project builds correctly with these configuration properties.

### Manual Verification
1. Run with **OpenAI**:
   - Configure `.env` with OpenAI values:
     ```properties
     OPENAI_BASE_URL=https://api.openai.com
     OPENAI_API_KEY=sk-proj-...
     PLANNING_MODEL=gpt-4o
     REASONING_MODEL=gpt-4o-mini
     ```
   - Start the container and verify a basic chat completion works.

2. Run with **Groq**:
   - Configure `.env` with Groq values:
     ```properties
     OPENAI_BASE_URL=https://api.groq.com/openai
     OPENAI_API_KEY=gsk_...
     PLANNING_MODEL=llama-3.1-70b-versatile
     REASONING_MODEL=llama-3.1-8b-instant
     ```
   - Start the container and verify that the app successfully communicates with Groq's endpoint and executes tasks.
