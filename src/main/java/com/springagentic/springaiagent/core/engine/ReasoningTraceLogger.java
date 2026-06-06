package com.springagentic.springaiagent.core.engine;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utility to log AI reasoning traces to a dedicated file to avoid console pollution
 * and performance issues with large text outputs.
 */
public class ReasoningTraceLogger {
    private static final String LOG_FILE = "app_reasoning.log";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static synchronized void logTrace(String threadId, String stepId, String type, String content) {
        try {
            Path path = Paths.get(LOG_FILE);
            if (!Files.exists(path)) {
                Files.createFile(path);
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
                writer.write(String.format("[%s] [Thread:%s] [Step:%s] [%s]\n", 
                    LocalDateTime.now().format(formatter), 
                    threadId != null ? threadId : "N/A", 
                    stepId != null ? stepId : "N/A", 
                    type));
                writer.write(content);
                writer.write("\n--------------------------------------------------------------------------------\n");
            }
        } catch (IOException e) {
            // Minimal console fallback
            System.err.println("CRITICAL: Failed to write to reasoning log: " + e.getMessage());
        }
    }
}
