package com.springagentic.springaiagent.core.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class BatchTransactionCoordinator {
    private final String batchId;
    private final AtomicReference<BatchState> state = new AtomicReference<>(BatchState.ACTIVE);
    private final List<Future<?>> runningFutures = new CopyOnWriteArrayList<>();

    public BatchTransactionCoordinator(String batchId) {
        this.batchId = batchId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void registerFuture(Future<?> future) {
        this.runningFutures.add(future);
    }

    public boolean checkHealth() {
        return state.get() != BatchState.INVALIDATED;
    }

    public void markMutated() {
        state.compareAndSet(BatchState.ACTIVE, BatchState.MUTATED);
    }

    public void invalidateBatch() {
        // Atomic transition. If we were not already INVALIDATED, we cancel all active futures
        if (state.getAndSet(BatchState.INVALIDATED) != BatchState.INVALIDATED) {
            for (Future<?> future : runningFutures) {
                if (!future.isDone()) {
                    future.cancel(true); // Interrupts the virtual thread executing the step
                }
            }
        }
    }

    public BatchState getState() {
        return state.get();
    }
}
