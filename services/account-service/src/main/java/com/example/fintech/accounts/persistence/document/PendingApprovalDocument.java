package com.example.fintech.accounts.persistence.document;

import com.example.fintech.accounts.domain.model.StatusReason;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "pending_approvals")
public class PendingApprovalDocument {
    @Id
    private String id;
    @Field("accountId")
    private String accountId;
    @Field("approverId")
    private String approverId;
    @Field("reason")
    private StatusReason reason;
    @Field("status")
    private String status;
    @Field("createdAt")
    private Instant createdAt;
    @Field("expiresAt")
    private Instant expiresAt;
    @Field("usedAt")
    private Instant usedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getApproverId() { return approverId; }
    public void setApproverId(String approverId) { this.approverId = approverId; }
    public StatusReason getReason() { return reason; }
    public void setReason(StatusReason reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
}
