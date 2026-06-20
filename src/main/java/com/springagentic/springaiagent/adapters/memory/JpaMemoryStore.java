package com.springagentic.springaiagent.adapters.memory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springagentic.springaiagent.core.domain.AgentContext;
import com.springagentic.springaiagent.core.spi.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Primary
public class JpaMemoryStore implements MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(JpaMemoryStore.class);

    private final AgentRunRepository repository;
    private final ObjectMapper objectMapper;

    public JpaMemoryStore(AgentRunRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper();
        // Configure Jackson to access all private fields directly via reflection, bypassing need for public setters
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    @Transactional
    public void saveRun(AgentContext context) {
        log.info("JPA MEMORY: Saving state for Run [{}] in DB", context.getRunId());
        try {
            String contextJson = objectMapper.writeValueAsString(context);
            AgentRunEntity entity = new AgentRunEntity(
                context.getRunId(),
                context.getThreadId(),
                context.getUserGoal(),
                context.getStatus(),
                contextJson,
                context.getTotalCostUsd()
            );
            repository.save(entity);
        } catch (Exception e) {
            log.error("JPA MEMORY: Failed to save run state to DB", e);
            throw new RuntimeException("DB Save Error: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentContext> loadRun(String threadId, String runId) {
        log.info("JPA MEMORY: Loading run state for Thread [{}], Run [{}] from DB", threadId, runId);
        return repository.findById(runId).map(entity -> {
            try {
                return objectMapper.readValue(entity.getContextJson(), AgentContext.class);
            } catch (Exception e) {
                log.error("JPA MEMORY: Failed to deserialize AgentContext", e);
                throw new RuntimeException("Deserialization Error: " + e.getMessage(), e);
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getThreadHistory(String threadId) {
        log.info("JPA MEMORY: Fetching thread history for Thread [{}] from DB", threadId);
        List<AgentRunEntity> runs = repository.findByThreadId(threadId);
        return runs.stream()
                .map(entity -> {
                    try {
                        AgentContext ctx = objectMapper.readValue(entity.getContextJson(), AgentContext.class);
                        return ctx.getFinalConclusion();
                    } catch (Exception e) {
                        log.error("JPA MEMORY: Failed to load conclusion for run in thread history", e);
                        return null;
                    }
                })
                .filter(conclusion -> conclusion != null)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public synchronized boolean claimSuspendedRun(String threadId, String runId) {
        log.info("JPA MEMORY: Claiming suspended run for Run [{}]", runId);
        Optional<AgentRunEntity> opt = repository.findById(runId);
        if (opt.isPresent()) {
            AgentRunEntity entity = opt.get();
            if ("AWAITING_APPROVAL".equals(entity.getStatus())) {
                try {
                    AgentContext context = objectMapper.readValue(entity.getContextJson(), AgentContext.class);
                    context.setStatus("RUNNING");
                    entity.setStatus("RUNNING");
                    entity.setTotalCostUsd(context.getTotalCostUsd());
                    entity.setContextJson(objectMapper.writeValueAsString(context));
                    repository.save(entity);
                    log.info("JPA MEMORY: Claimed suspended run successfully.");
                    return true;
                } catch (Exception e) {
                    log.error("JPA MEMORY: Failed to update claimed suspended run state", e);
                    throw new RuntimeException("Claim suspended error: " + e.getMessage(), e);
                }
            }
        }
        return false;
    }
}
