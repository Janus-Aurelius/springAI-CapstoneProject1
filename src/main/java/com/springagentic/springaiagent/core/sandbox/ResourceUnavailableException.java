package com.springagentic.springaiagent.core.sandbox;

public class ResourceUnavailableException extends RuntimeException {
    public ResourceUnavailableException(String message) {
        super(message);
    }
    public ResourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
