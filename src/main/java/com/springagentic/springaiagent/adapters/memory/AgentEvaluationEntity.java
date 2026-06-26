//package com.springagentic.springaiagent.adapters.memory;
//
//import jakarta.persistence.*;
//import java.time.LocalDateTime;
//import java.util.UUID;
//
//@Entity
//@Table(name = "agent_evaluations", schema = "evaluation")
//public class AgentEvaluationEntity {
//
//    @Id
//    private UUID id;
//
//    @Column(name = "thread_id", nullable = false)
//    private String threadId;
//
//    @Column(name = "run_id", nullable = false)
//    private String runId;
//
//    @Column(name = "is_goal_met", nullable = false)
//    private boolean isGoalMet;
//
//    @Column(name = "alignment_score", nullable = false)
//    private double alignmentScore;
//
//    @Column(name = "safety_score", nullable = false)
//    private double safetyScore;
//
//    @Column(name = "explanation", length = 4000)
//    private String explanation;
//
//    @Column(name = "created_at", nullable = false)
//    private LocalDateTime createdAt;
//
//    @PrePersist
//    public void prePersist() {
//        if (this.id == null) {
//            this.id = UUID.randomUUID();
//        }
//        this.createdAt = LocalDateTime.now();
//    }
//
//    public AgentEvaluationEntity() {}
//
//    public AgentEvaluationEntity(String threadId, String runId, boolean isGoalMet, double alignmentScore, double safetyScore, String explanation) {
//        this.threadId = threadId;
//        this.runId = runId;
//        this.isGoalMet = isGoalMet;
//        this.alignmentScore = alignmentScore;
//        this.safetyScore = safetyScore;
//        this.explanation = explanation;
//    }
//
//    public UUID getId() {
//        return id;
//    }
//
//    public void setId(UUID id) {
//        this.id = id;
//    }
//
//    public String getThreadId() {
//        return threadId;
//    }
//
//    public void setThreadId(String threadId) {
//        this.threadId = threadId;
//    }
//
//    public String getRunId() {
//        return runId;
//    }
//
//    public void setRunId(String runId) {
//        this.runId = runId;
//    }
//
//    public boolean isGoalMet() {
//        return isGoalMet;
//    }
//
//    public void setGoalMet(boolean goalMet) {
//        isGoalMet = goalMet;
//    }
//
//    public double getAlignmentScore() {
//        return alignmentScore;
//    }
//
//    public void setAlignmentScore(double alignmentScore) {
//        this.alignmentScore = alignmentScore;
//    }
//
//    public double getSafetyScore() {
//        return safetyScore;
//    }
//
//    public void setSafetyScore(double safetyScore) {
//        this.safetyScore = safetyScore;
//    }
//
//    public String getExplanation() {
//        return explanation;
//    }
//
//    public void setExplanation(String explanation) {
//        this.explanation = explanation;
//    }
//
//    public LocalDateTime getCreatedAt() {
//        return createdAt;
//    }
//
//    public void setCreatedAt(LocalDateTime createdAt) {
//        this.createdAt = createdAt;
//    }
//}
