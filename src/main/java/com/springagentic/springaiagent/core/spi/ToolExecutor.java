package com.springagentic.springaiagent.core.spi;

public interface ToolExecutor {
    // Returns true if this executor handles the given tool
    boolean supports (String toolName);

    // Executes and returns JSON string
    String execute(String toolName, String jsonArgs);
}
