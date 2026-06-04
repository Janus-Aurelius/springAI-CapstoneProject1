package com.springagentic.springaiagent.core.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.springagentic.springaiagent.adapters.sandbox.DockerManagedSandbox;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class McpContainerFactory {
    private static final Logger log = LoggerFactory.getLogger(McpContainerFactory.class);

    @Value("${sandbox.core-pool-size:2}")
    private int corePoolSize;

    @Value("${sandbox.max-pool-size:5}")
    private int maxPoolSize;

    @Value("${sandbox.compute-image:python:3.12-alpine}")
    private String computeImage;

    @Value("${sandbox.fetch-image:alpine:latest}")
    private String fetchImage;

    private DockerClient dockerClient;
    
    private final BlockingQueue<String> computeQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> fetchQueue = new LinkedBlockingQueue<>();

    private final AtomicInteger leasedComputeCount = new AtomicInteger(0);
    private final AtomicInteger leasedFetchCount = new AtomicInteger(0);

    private final Set<String> activeContainerIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @PostConstruct
    public void init() {
        log.info("Initializing Docker client...");
        try {
            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();
            this.dockerClient = DockerClientImpl.getInstance(config, httpClient);

            pullImageIfNeeded(computeImage);
            pullImageIfNeeded(fetchImage);

            log.info("Warming up COMPUTE pool (size: {})...", corePoolSize);
            for (int i = 0; i < corePoolSize; i++) {
                String id = createContainer(SandboxProfile.COMPUTE);
                computeQueue.offer(id);
            }

            log.info("Warming up FETCH pool (size: {})...", corePoolSize);
            for (int i = 0; i < corePoolSize; i++) {
                String id = createContainer(SandboxProfile.FETCH);
                fetchQueue.offer(id);
            }
        } catch (Exception e) {
            log.error("Failed to initialize McpContainerFactory. Containers will not be available.", e);
        }
    }

    private void pullImageIfNeeded(String image) {
        log.info("Checking/pulling image: {}", image);
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (Exception e) {
            try {
                dockerClient.pullImageCmd(image).start().awaitCompletion();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted pulling image " + image, ie);
            }
        }
    }

    private String createContainer(SandboxProfile profile) {
        HostConfig hostConfig;
        List<String> env = new ArrayList<>();
        String imageToUse = (profile == SandboxProfile.COMPUTE) ? computeImage : fetchImage;
        if (profile == SandboxProfile.COMPUTE) {
            hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode("none")
                    .withReadonlyRootfs(true)
                    .withTmpFs(Map.of("/workspace", "rw,noexec,nosuid,size=64m"))
                    .withMemory(256 * 1024 * 1024L)
                    .withCpuQuota(50000L)
                    .withCpuPeriod(100000L);
        } else {
            hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode("sandbox_net")
                    .withCapDrop(Capability.ALL)
                    .withMemory(256 * 1024 * 1024L)
                    .withCpuQuota(50000L)
                    .withCpuPeriod(100000L);
            env.add("HTTP_PROXY=http://squid:3128");
            env.add("HTTPS_PROXY=http://squid:3128");
        }

        CreateContainerResponse response = dockerClient.createContainerCmd(imageToUse)
                .withHostConfig(hostConfig)
                .withEnv(env)
                .withCmd("sleep", "3600")
                .exec();

        String containerId = response.getId();
        dockerClient.startContainerCmd(containerId).exec();
        activeContainerIds.add(containerId);
        return containerId;
    }

    public ManagedSandbox lease(SandboxProfile profile) {
        if (dockerClient == null) {
            throw new ResourceUnavailableException("Docker client is not initialized.");
        }

        BlockingQueue<String> queue = (profile == SandboxProfile.COMPUTE) ? computeQueue : fetchQueue;
        AtomicInteger leasedCount = (profile == SandboxProfile.COMPUTE) ? leasedComputeCount : leasedFetchCount;

        try {
            // 1. Try to poll queue for 500ms
            String containerId = queue.poll(500, TimeUnit.MILLISECONDS);
            if (containerId != null) {
                leasedCount.incrementAndGet();
                return new DockerManagedSandbox(containerId, dockerClient, this, false);
            }

            // 2. Queue empty. Check if we can burst.
            int currentTotal = leasedCount.get() + queue.size();
            if (currentTotal < maxPoolSize) {
                // Cold burst container
                log.info("Pool for {} empty, bursting cold container", profile);
                String newId = createContainer(profile);
                leasedCount.incrementAndGet();
                return new DockerManagedSandbox(newId, dockerClient, this, true);
            }

            // 3. Max pool size reached. Block on queue up to 10s total timeout (minus 500ms already spent)
            containerId = queue.poll(9500, TimeUnit.MILLISECONDS);
            if (containerId != null) {
                leasedCount.incrementAndGet();
                return new DockerManagedSandbox(containerId, dockerClient, this, false);
            }

            throw new ResourceUnavailableException("Sandbox pool exhausted for profile: " + profile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceUnavailableException("Lease request interrupted", e);
        } catch (Exception e) {
            throw new ResourceUnavailableException("Failed to lease sandbox container for " + profile, e);
        }
    }

    public void returnToPool(String containerId, boolean isEphemeral) {
        AtomicInteger leasedCount = activeContainerIds.contains(containerId) ? 
                (leasedComputeCount.get() > 0 ? leasedComputeCount : leasedFetchCount) : null;

        if (isEphemeral) {
            destroyContainerAsync(containerId);
            if (leasedCount != null) {
                leasedCount.decrementAndGet();
            }
        } else {
            // Attempt clean reset
            executorService.submit(() -> {
                try {
                    // Try to execute a reset command. Note compute is read-only root but has writeable tmpfs /workspace.
                    // We can wipe /workspace.
                    dockerClient.execStartCmd(
                        dockerClient.execCreateCmd(containerId).withCmd("rm", "-rf", "/workspace/*").exec().getId()
                    ).exec(new com.github.dockerjava.core.command.ExecStartResultCallback(System.out, System.err))
                     .awaitCompletion(2, TimeUnit.SECONDS);

                    // Re-enqueue
                    if (computeQueue.size() + leasedComputeCount.get() < maxPoolSize) {
                        // Place back based on where it came from or default.
                        // We need to know which profile it belongs to.
                        // We can check the network settings of the container to determine its profile.
                        var inspect = dockerClient.inspectContainerCmd(containerId).exec();
                        String netMode = inspect.getHostConfig().getNetworkMode();
                        if ("none".equals(netMode)) {
                            computeQueue.offer(containerId);
                        } else {
                            fetchQueue.offer(containerId);
                        }
                    } else {
                        destroyContainer(containerId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to reset container {}, replacing it", containerId, e);
                    // Self-heal: destroy and replace
                    destroyContainer(containerId);
                    try {
                        var inspect = dockerClient.inspectContainerCmd(containerId).exec();
                        String netMode = inspect.getHostConfig().getNetworkMode();
                        SandboxProfile profile = "none".equals(netMode) ? SandboxProfile.COMPUTE : SandboxProfile.FETCH;
                        String newId = createContainer(profile);
                        if (profile == SandboxProfile.COMPUTE) {
                            computeQueue.offer(newId);
                        } else {
                            fetchQueue.offer(newId);
                        }
                    } catch (Exception ex) {
                        log.error("Failed to replace container", ex);
                    }
                } finally {
                    if (leasedCount != null) {
                        leasedCount.decrementAndGet();
                    }
                }
            });
        }
    }

    private void destroyContainerAsync(String containerId) {
        executorService.submit(() -> destroyContainer(containerId));
    }

    private void destroyContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(2).exec();
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("Failed to destroy container: {}", containerId, e);
        } finally {
            activeContainerIds.remove(containerId);
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up sandbox containers...");
        executorService.shutdownNow();
        if (dockerClient != null) {
            for (String containerId : activeContainerIds) {
                try {
                    dockerClient.stopContainerCmd(containerId).withTimeout(1).exec();
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    // Testing getters
    public int getComputeQueueSize() { return computeQueue.size(); }
    public int getFetchQueueSize() { return fetchQueue.size(); }
    public int getLeasedComputeCount() { return leasedComputeCount.get(); }
    public int getLeasedFetchCount() { return leasedFetchCount.get(); }
    public DockerClient getDockerClient() { return dockerClient; }
}
