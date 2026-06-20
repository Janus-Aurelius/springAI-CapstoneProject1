package com.springagentic.springaiagent.adapters.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface AgentEvaluationRepository extends JpaRepository<AgentEvaluationEntity, UUID> {
}
