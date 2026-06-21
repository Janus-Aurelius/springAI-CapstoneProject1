package com.springagentic.springaiagent.core.security;

public interface SecretRedactor {
    /**
     * Throws SecurityException if any known secret value is detected in jsonArgs.
     */
    void assertClean(String toolName, String jsonArgs);
}
