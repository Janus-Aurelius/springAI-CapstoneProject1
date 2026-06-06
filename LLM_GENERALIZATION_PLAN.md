# LLM Generalization & Resilience Plan

## Phase 1: Configuration & Property Binding
- [x] Define `LlmProperties` record with Guardrails and Provider configurations.
- [x] Enable `@ConfigurationProperties` and exclude `OpenAiAutoConfiguration`.
- [x] Convert `application.properties` to `application.yml` with multi-provider support.

## Phase 2: The Fleet Registry (Infrastructure)
- [x] Implement `LlmProviderRegistry` for managing multiple `OpenAiChatModel` instances.
- [x] Add explicit HTTP connection and read timeouts to the `OpenAIOkHttpClient`.

## Phase 3: The Resilient Router & Failover Logic
- [x] Implement `LlmRouter` interface and `ResilientLlmRouter`.
- [x] Implement basic try-catch failover with a 30s cooldown map.
- [x] Implement Reactive TTFT (Time-To-First-Token) monitoring using `stream()` and `Flux.timeout`.

## Phase 4: Failover-Aware Context Management
- [x] Implement `ContextManager` with JTokkit for token estimation.
- [x] Implement sliding window truncation with a 10% safety buffer.

## Phase 5: Heuristic Guardrails (Agent Loop Upgrades)
- [x] Add `actionHistory` and `totalTokensConsumed` to `AgentContext`.
- [x] Implement Semantic Stagnation Detection in `ExecutionEngine`.
- [x] Externalize all hardcoded limits to `LlmProperties`.

## Phase 6: Integration & Cleanup
- [x] Refactor `SpringAiLlmProvider` to use `LlmRouter`.
- [x] Update `EngineConfig` to inject new dependencies.
- [x] Fix compilation errors and update existing tests.
- [x] Add integration tests for failover scenarios (`LlmFailoverIntegrationTest`).
- [x] Clean up any remaining hardcoded Groq references in `AGENT_GUIDE.md` and `.env.example`.
