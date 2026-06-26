//package com.springagentic.springaiagent.adapters.memory;
//
//import com.springagentic.springaiagent.core.domain.AgentContext;
//import com.springagentic.springaiagent.core.spi.MemoryStore;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.stream.Collectors;
//
//@Component
//public class InMemoryStore implements MemoryStore {
//    private static final Logger log = LoggerFactory.getLogger(InMemoryStore.class);
//    // Key format: "threadId:runId"
//    private final Map<String, AgentContext> store = new ConcurrentHashMap<>();
//
//    @Override
//    public void saveRun(AgentContext context) {
//        String key = context.getThreadId() + ":" + context.getRunId();
//        store.put(key, context);
//        log.info("MEMORY: Saved state for Run [{}]", context.getRunId());
//    }
//
//    @Override
//    public Optional<AgentContext> loadRun(String threadId, String runId) {
//        return Optional.ofNullable(store.get(threadId + ":" + runId));
//    }
//
//    @Override
//    public List<String> getThreadHistory(String threadId) {
//        // Return all final conclusions from previous runs in this thread
//        return store.values().stream()
//                .filter(ctx -> ctx.getThreadId().equals(threadId))
//                .filter(AgentContext::isTerminated)
//                .map(AgentContext::getFinalConclusion)
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public synchronized boolean claimSuspendedRun(String threadId, String runId) {
//        Optional<AgentContext> opt = loadRun(threadId, runId);
//        if (opt.isPresent()) {
//            AgentContext context = opt.get();
//            if ("AWAITING_APPROVAL".equals(context.getStatus())) {
//                context.setStatus("RUNNING");
//                saveRun(context);
//                return true;
//            }
//        }
//        return false;
//    }
//}
