package com.babyrecord.model;

import java.time.LocalDateTime;

public class BabyEvent {
    private Long id;
    private Long babyId;
    private Long operatorId;
    private String operatorName;
    private Long endOperatorId;
    private String endOperatorName;
    private String clientEventId;
    private String eventType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer amountMl;
    private String eventData;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBabyId() { return babyId; }
    public void setBabyId(Long babyId) { this.babyId = babyId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public Long getEndOperatorId() { return endOperatorId; }
    public void setEndOperatorId(Long endOperatorId) { this.endOperatorId = endOperatorId; }
    public String getEndOperatorName() { return endOperatorName; }
    public void setEndOperatorName(String endOperatorName) { this.endOperatorName = endOperatorName; }
    public String getClientEventId() { return clientEventId; }
    public void setClientEventId(String clientEventId) { this.clientEventId = clientEventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public Integer getAmountMl() { return amountMl; }
    public void setAmountMl(Integer amountMl) { this.amountMl = amountMl; }
    public String getEventData() { return eventData; }
    public void setEventData(String eventData) { this.eventData = eventData; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
