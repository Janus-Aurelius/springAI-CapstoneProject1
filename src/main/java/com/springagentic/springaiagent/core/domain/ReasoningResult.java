package com.springagentic.springaiagent.core.domain;

public sealed interface ReasoningResult {

    record Action(String toolName, String jsonArgs) implements ReasoningResult {}

    // Agent triggers dynamic replanning
    record Replan(String reason) implements ReasoningResult {}

    record FinalAnswer(String text) implements ReasoningResult {}

    // Tool execution failed structurally (e.g. system down)
    record Error(String errorMessage) implements ReasoningResult {}
}