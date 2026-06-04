package com.springagentic.springaiagent.adapters.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SecretRedactorTest {

    @Test
    public void testAssertCleanWithCleanArguments() {
        DefaultSecretRedactor redactor = new DefaultSecretRedactor();
        redactor.registerSecret("super-secret-openai-key-value-12345");
        
        // Should not throw exception
        assertDoesNotThrow(() -> redactor.assertClean("some_tool", "{\"code\": \"print('hello')\"}"));
    }

    @Test
    public void testAssertCleanWithSensitiveLeakage() {
        DefaultSecretRedactor redactor = new DefaultSecretRedactor();
        redactor.registerSecret("super-secret-openai-key-value-12345");

        // Should throw SecurityException
        assertThrows(SecurityException.class, () -> 
            redactor.assertClean("some_tool", "{\"api_key\": \"super-secret-openai-key-value-12345\"}")
        );
    }
}
