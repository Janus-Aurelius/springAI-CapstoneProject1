package com.springagentic.springaiagent.core.sandbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "sandbox.core-pool-size=1",
    "sandbox.max-pool-size=2",
    "sandbox.compute-image=python:3.12-alpine",
    "sandbox.fetch-image=alpine:latest",
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryAutoConfiguration," +
        "org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration," +
        "org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=update"
})
public class McpContainerFactoryTest {

    @Autowired(required = false)
    private McpContainerFactory factory;

    private boolean isDockerAvailable() {
        if (factory == null || factory.getDockerClient() == null) {
            return false;
        }
        try {
            factory.getDockerClient().pingCmd().exec();
            return factory.getDockerClient().listNetworksCmd().exec().stream()
                    .anyMatch(net -> "sandbox_net".equals(net.getName()));
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void testFactoryInitAndLease() throws Exception {
        if (!isDockerAvailable()) {
            System.out.println("Skipping test because Docker is not available.");
            return;
        }

        // Test that core pool size is 1 immediately
        assertEquals(1, factory.getComputeQueueSize());
        assertEquals(1, factory.getFetchQueueSize());

        // Lease 1 COMPUTE container
        try (ManagedSandbox sandbox1 = factory.lease(SandboxProfile.COMPUTE)) {
            assertNotNull(sandbox1);
            assertEquals(0, factory.getComputeQueueSize());
            assertEquals(1, factory.getLeasedComputeCount());

            // Burst lease: we allow up to maxPoolSize (2)
            try (ManagedSandbox sandbox2 = factory.lease(SandboxProfile.COMPUTE)) {
                assertNotNull(sandbox2);
                assertEquals(2, factory.getLeasedComputeCount() + factory.getComputeQueueSize());
                
                // Exceed maxPoolSize (2) -> should throw ResourceUnavailableException
                assertThrows(ResourceUnavailableException.class, () -> {
                    factory.lease(SandboxProfile.COMPUTE);
                });
            }
        }

        // Wait a short moment for async return to pool
        Thread.sleep(1500);

        // Core container should be returned to pool
        assertEquals(1, factory.getComputeQueueSize());
        assertEquals(0, factory.getLeasedComputeCount());
    }
}
