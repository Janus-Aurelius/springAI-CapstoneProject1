package com.springagentic.springaiagent.core.sandbox;

import java.time.Duration;

public interface ManagedSandbox extends AutoCloseable {
    String getContainerId();
    String executeCommand(String command, Duration timeout) throws Exception;
    CpuMetrics getLiveCpuUsage();
    record CpuMetrics(double cpuPercentage, long memoryUsageBytes) {}
    
    @Override
    void close();
}
