package com.springagentic.springaiagent.adapters.security;

import com.springagentic.springaiagent.core.security.SecretRedactor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultSecretRedactor implements SecretRedactor {
    private static final Logger log = LoggerFactory.getLogger(DefaultSecretRedactor.class);

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    private final List<String> secrets = new ArrayList<>();

    @PostConstruct
    public void init() {
        addSecret(openAiApiKey);
    }

    private void addSecret(String value) {
        if (value != null && !value.isBlank() && value.length() >= 8 && !value.contains("YOUR_OPENAI_API_KEY")) {
            secrets.add(value);
        }
    }

    @Override
    public void assertClean(String toolName, String jsonArgs) {
        if (jsonArgs == null || jsonArgs.isBlank()) {
            return;
        }
        for (String secret : secrets) {
            if (jsonArgs.contains(secret)) {
                log.error("SECURITY_VIOLATION: Secret leakage detected in arguments for tool: {}", toolName);
                throw new SecurityException("DLP: Potential secret leakage detected in args for tool: " + toolName);
            }
        }
    }
    
    // For testing purposes
    public void registerSecret(String secret) {
        addSecret(secret);
    }
}
