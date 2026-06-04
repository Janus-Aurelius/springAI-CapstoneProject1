package com.springagentic.springaiagent.core.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryAutoConfiguration," +
        "org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration," +
        "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration"
})
public class DockerManagedSandboxTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public javax.sql.DataSource dataSource() {
            return org.mockito.Mockito.mock(javax.sql.DataSource.class);
        }
    }

    @Autowired(required = false)
    private McpContainerFactory factory;

    private boolean isDockerAvailable() {
        if (factory == null || factory.getDockerClient() == null) {
            return false;
        }
        try {
            factory.getDockerClient().pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void testExecuteCommandAndMetrics() throws Exception {
        if (!isDockerAvailable()) {
            System.out.println("Skipping test because Docker is not available.");
            return;
        }

        try (ManagedSandbox sandbox = factory.lease(SandboxProfile.COMPUTE)) {
            assertNotNull(sandbox);
            
            // Execute simple command
            String output = sandbox.executeCommand("echo 'Hello Sandbox'", Duration.ofSeconds(5));
            assertEquals("Hello Sandbox", output);

            // Execute Python command
            String pythonOutput = sandbox.executeCommand("python3 -c \"print(2+2)\"", Duration.ofSeconds(5));
            assertEquals("4", pythonOutput);

            // Verify metrics
            ManagedSandbox.CpuMetrics metrics = sandbox.getLiveCpuUsage();
            assertNotNull(metrics);
            assertTrue(metrics.cpuPercentage() >= 0.0);
            assertTrue(metrics.memoryUsageBytes() >= 0L);
        }
    }
}
