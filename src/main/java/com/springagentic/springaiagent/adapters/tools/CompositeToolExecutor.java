package com.springagentic.springaiagent.adapters.tools;

import com.springagentic.springaiagent.core.spi.ToolExecutor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Primary
public class CompositeToolExecutor implements ToolExecutor {

    private final List<ToolExecutor> executors;

    public CompositeToolExecutor(List<ToolExecutor> executors) {
        // Exclude ourselves to prevent infinite recursion
        this.executors = executors.stream()
                .filter(e -> e != this)
                .toList();
    }

    @Override
    public boolean supports(String toolName) {
        return executors.stream().anyMatch(e -> e.supports(toolName));
    }

    @Override
    public String execute(String toolName, String jsonArgs) {
        for (ToolExecutor executor : executors) {
            if (executor.supports(toolName)) {
                return executor.execute(toolName, jsonArgs);
            }
        }
        throw new IllegalArgumentException("Unsupported tool: " + toolName);
    }
}
