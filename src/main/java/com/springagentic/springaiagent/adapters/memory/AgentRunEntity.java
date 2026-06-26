//package com.springagentic.springaiagent.adapters.memory;
//
//import jakarta.persistence.Column;
//import jakarta.persistence.Entity;
//import jakarta.persistence.Id;
//import jakarta.persistence.Lob;
//import jakarta.persistence.PrePersist;
//import jakarta.persistence.PreUpdate;
//import jakarta.persistence.Table;
//import java.time.LocalDateTime;
//import org.hibernate.annotations.JdbcTypeCode;
//import org.hibernate.type.SqlTypes;
//
//@Entity
//@Table(name = "agent_runs")
//public class AgentRunEntity {
//
//    @Id
//    @Column(name = "run_id")
//    private String runId;
//
//    @Column(name = "thread_id", nullable = false)
//    private String threadId;
//
//    @Column(name = "user_goal", columnDefinition = "TEXT")
//    private String userGoal;
//
//    @Column(name = "status", nullable = false)
//    private String status;
//
//    @JdbcTypeCode(SqlTypes.JSON)
//    @Column(name = "context_json")
//    private String contextJson;
//
//    @Column(name = "total_cost_usd", nullable = false)
//    private double totalCostUsd = 0.0;
//
//    @Column(name = "updated_at")
//    private LocalDateTime updatedAt;
//
//    public AgentRunEntity() {}
//
//    public AgentRunEntity(String runId, String threadId, String userGoal, String status, String contextJson) {
//        this.runId = runId;
//        this.threadId = threadId;
//        this.userGoal = userGoal;
//        this.status = status;
//        this.contextJson = contextJson;
//    }
//
//    public AgentRunEntity(String runId, String threadId, String userGoal, String status, String contextJson, double totalCostUsd) {
//        this.runId = runId;
//        this.threadId = threadId;
//        this.userGoal = userGoal;
//        this.status = status;
//        this.contextJson = contextJson;
//        this.totalCostUsd = totalCostUsd;
//    }
//
//    @PrePersist
//    @PreUpdate
//    protected void onUpdate() {
//        this.updatedAt = LocalDateTime.now();
//    }
//
//    public String getRunId() { return runId; }
//    public void setRunId(String runId) { this.runId = runId; }
//
//    public String getThreadId() { return threadId; }
//    public void setThreadId(String threadId) { this.threadId = threadId; }
//
//    public String getUserGoal() { return userGoal; }
//    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }
//
//    public String getStatus() { return status; }
//    public void setStatus(String status) { this.status = status; }
//
//    public String getContextJson() { return contextJson; }
//    public void setContextJson(String contextJson) { this.contextJson = contextJson; }
//
//    public double getTotalCostUsd() { return totalCostUsd; }
//    public void setTotalCostUsd(double totalCostUsd) { this.totalCostUsd = totalCostUsd; }
//
//    public LocalDateTime getUpdatedAt() { return updatedAt; }
//    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
//}
