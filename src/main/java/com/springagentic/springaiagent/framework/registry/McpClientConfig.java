package com.springagentic.springaiagent.framework.registry;

import com.springagentic.springaiagent.adapters.web.McpProgressWebSocketHandler;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

@Configuration
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    private final McpProgressWebSocketHandler progressWebSocketHandler;

    public McpClientConfig(@Lazy McpProgressWebSocketHandler progressWebSocketHandler) {
        this.progressWebSocketHandler = progressWebSocketHandler;
    }

    @Value("${mcp.server.postgres.url:}")
    private String postgresUrl;

    @Value("${mcp.server.github.url:}")
    private String githubUrl;

    @Value("${mcp.server.puppeteer.url:}")
    private String puppeteerUrl;

    @Value("${mcp.server.tavily-search.url:}")
    private String tavilySearchUrl;

    @Bean
    public McpSyncClient postgresMcpClient() {
        return postgresUrl.isEmpty() ? null : createMcpClient("postgres", postgresUrl);
    }

    @Bean
    public McpSyncClient githubMcpClient() {
        return githubUrl.isEmpty() ? null : createMcpClient("github", githubUrl);
    }

    @Bean
    public McpSyncClient puppeteerMcpClient() {
        return puppeteerUrl.isEmpty() ? null : createMcpClient("puppeteer", puppeteerUrl);
    }

    @Bean
    public McpSyncClient tavilySearchMcpClient() {
        return tavilySearchUrl.isEmpty() ? null : createMcpClient("tavily-search", tavilySearchUrl);
    }

    private McpSyncClient createMcpClient(String name, String url) {
        log.info("Configuring MCP Client [{}] at URL: {}", name, url);
        try {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(url)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            McpSyncClient client = McpClient.sync(transport)
                    .clientInfo(new McpSchema.Implementation("springaiagent", "1.0.0"))
                    .requestTimeout(Duration.ofSeconds(120))
                    .initializationTimeout(Duration.ofSeconds(30))
                    .progressConsumer(notification -> {
                        String msg = notification.message() != null ? notification.message() : "";
                        Double progress = notification.progress();
                        Double total = notification.total();
                        String progressInfo;
                        if (progress != null && total != null && total > 0) {
                            progressInfo = String.format("Progress: %.0f%% (%s)", (progress / total) * 100, msg);
                        } else if (progress != null) {
                            progressInfo = String.format("Progress: %.0f (%s)", progress, msg);
                        } else {
                            progressInfo = msg;
                        }
                        log.info("MCP Client [{}] progress update: {}", name, progressInfo);
                        if (progressWebSocketHandler != null) {
                            progressWebSocketHandler.broadcastProgress(name, progressInfo);
                        }
                    })
                    .build();

            try {
                client.initialize();
                log.info("Successfully initialized MCP Client [{}]", name);
            } catch (Exception e) {
                log.warn("Failed to initialize MCP Client [{}] at {}: {}. Connection will be retried on usage.", 
                        name, url, e.getMessage());
            }
            return client;
        } catch (Exception e) {
            log.error("Failed to build MCP Client [{}] due to error", name, e);
            throw new RuntimeException("Error building MCP Client: " + name, e);
        }
    }
}
