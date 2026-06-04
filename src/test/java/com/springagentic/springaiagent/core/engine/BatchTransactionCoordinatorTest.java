package com.springagentic.springaiagent.core.engine;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class BatchTransactionCoordinatorTest {

    @Test
    public void testStateTransitions() {
        BatchTransactionCoordinator coordinator = new BatchTransactionCoordinator("test-batch");
        
        // Initial state
        assertEquals(BatchState.ACTIVE, coordinator.getState());
        assertTrue(coordinator.checkHealth());

        // Transition to MUTATED
        coordinator.markMutated();
        assertEquals(BatchState.MUTATED, coordinator.getState());
        assertTrue(coordinator.checkHealth());

        // Transition to INVALIDATED
        coordinator.invalidateBatch();
        assertEquals(BatchState.INVALIDATED, coordinator.getState());
        assertFalse(coordinator.checkHealth());

        // Repeated mutation call should not change state from INVALIDATED
        coordinator.markMutated();
        assertEquals(BatchState.INVALIDATED, coordinator.getState());
    }

    @Test
    public void testActiveCancellation() {
        BatchTransactionCoordinator coordinator = new BatchTransactionCoordinator("test-batch");

        // Mock futures
        Future<?> mockFuture1 = mock(Future.class);
        Future<?> mockFuture2 = mock(Future.class);
        Future<?> mockFutureCompleted = mock(Future.class);

        // Configure mock completions
        when(mockFuture1.isDone()).thenReturn(false);
        when(mockFuture2.isDone()).thenReturn(false);
        when(mockFutureCompleted.isDone()).thenReturn(true);

        // Register futures
        coordinator.registerFuture(mockFuture1);
        coordinator.registerFuture(mockFuture2);
        coordinator.registerFuture(mockFutureCompleted);

        // Trigger poison pill invalidation
        coordinator.invalidateBatch();

        // Verify active cancellation is called on incomplete futures only
        verify(mockFuture1, times(1)).cancel(true);
        verify(mockFuture2, times(1)).cancel(true);
        verify(mockFutureCompleted, never()).cancel(anyBoolean());
    }
}
