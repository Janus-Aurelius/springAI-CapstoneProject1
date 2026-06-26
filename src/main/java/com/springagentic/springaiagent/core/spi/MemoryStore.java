//package com.springagentic.springaiagent.core.spi;
//
//import com.springagentic.springaiagent.core.domain.AgentContext;
//
//import java.util.List;
//import java.util.Optional;
//
//public interface MemoryStore {
//    // Saves the current state of a specific run
//    void saveRun(AgentContext context);
//
//    // Loads a specific run
//    Optional<AgentContext> loadRun(String threadId, String runId);
//
//    // Loads the past historical conclusions for a user's thread
//    List<String> getThreadHistory(String threadId);
//
//    // Atomically transitions run status from AWAITING_APPROVAL to RUNNING to prevent double-resumptions
//    boolean claimSuspendedRun(String threadId, String runId);
//}