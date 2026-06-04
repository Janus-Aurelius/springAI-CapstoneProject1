package com.springagentic.springaiagent.adapters.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.springagentic.springaiagent.core.sandbox.ManagedSandbox;
import com.springagentic.springaiagent.core.sandbox.McpContainerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DockerManagedSandbox implements ManagedSandbox {
    private final String containerId;
    private final DockerClient dockerClient;
    private final McpContainerFactory factory;
    private final boolean isEphemeral;

    public DockerManagedSandbox(String containerId, DockerClient dockerClient, McpContainerFactory factory, boolean isEphemeral) {
        this.containerId = containerId;
        this.dockerClient = dockerClient;
        this.factory = factory;
        this.isEphemeral = isEphemeral;
    }

    @Override
    public String getContainerId() {
        return containerId;
    }

    @Override
    public String executeCommand(String command, Duration timeout) throws Exception {
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c", command)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr);
        dockerClient.execStartCmd(execCreate.getId()).exec(callback);

        boolean completed = callback.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            throw new TimeoutException("Command execution timed out after " + timeout.toSeconds() + " seconds");
        }

        String errStr = stderr.toString().trim();
        String outStr = stdout.toString().trim();

        if (!errStr.isEmpty()) {
            return outStr + "\nError: " + errStr;
        }
        return outStr;
    }

    @Override
    public CpuMetrics getLiveCpuUsage() {
        try {
            StatsCallback callback = new StatsCallback();
            dockerClient.statsCmd(containerId).withNoStream(true).exec(callback);
            Statistics stats = callback.getStats(3000);
            
            if (stats == null) {
                return new CpuMetrics(0.0, 0L);
            }

            long memoryUsageBytes = stats.getMemoryStats() != null && stats.getMemoryStats().getUsage() != null ? stats.getMemoryStats().getUsage() : 0L;
            double cpuPercentage = 0.0;

            CpuStatsConfig cpuStats = stats.getCpuStats();
            CpuStatsConfig precpuStats = stats.getPreCpuStats();
            if (cpuStats != null && precpuStats != null && cpuStats.getCpuUsage() != null && precpuStats.getCpuUsage() != null) {
                long containerUsage = cpuStats.getCpuUsage().getTotalUsage() != null ? cpuStats.getCpuUsage().getTotalUsage() : 0L;
                long preContainerUsage = precpuStats.getCpuUsage().getTotalUsage() != null ? precpuStats.getCpuUsage().getTotalUsage() : 0L;
                long cpuDelta = containerUsage - preContainerUsage;
                
                long systemUsage = cpuStats.getSystemCpuUsage() != null ? cpuStats.getSystemCpuUsage() : 0L;
                long preSystemUsage = precpuStats.getSystemCpuUsage() != null ? precpuStats.getSystemCpuUsage() : 0L;
                long systemDelta = systemUsage - preSystemUsage;

                Long onlineCpuCount = cpuStats.getOnlineCpus();
                int numCpus = onlineCpuCount != null ? onlineCpuCount.intValue() : 1;

                if (systemDelta > 0 && cpuDelta > 0) {
                    cpuPercentage = ((double) cpuDelta / systemDelta) * numCpus * 100.0;
                }
            }
            return new CpuMetrics(cpuPercentage, memoryUsageBytes);
        } catch (Exception e) {
            return new CpuMetrics(0.0, 0L);
        }
    }

    @Override
    public void close() {
        factory.returnToPool(containerId, isEphemeral);
    }

    private static class StatsCallback extends com.github.dockerjava.api.async.ResultCallbackTemplate<StatsCallback, Statistics> {
        private Statistics stats;
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onNext(Statistics object) {
            this.stats = object;
            latch.countDown();
        }

        public Statistics getStats(long timeoutMs) throws InterruptedException {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return stats;
        }
    }
}
