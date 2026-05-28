package com.example.fintech.accounts.persistence.document;

import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.AccountType;
import com.example.fintech.accounts.domain.model.StatusReason;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "accounts")
public class AccountDocument {
    @Id
    private String id;
    @Field("ownerUserId")
    private String ownerUserId;
    @Field("currency")
    private String currency;
    @Field("type")
    private AccountType type;
    @Field("label")
    private String label;
    @Field("balance")
    private long balance;
    @Field("status")
    private AccountStatus status;
    @Field("statusReason")
    private StatusReason statusReason;
    @Field("version")
    private long version;
    @Field("idempotencyKey")
    private String idempotencyKey;
    @Field("payloadHash")
    private String payloadHash;
    @Field("createdAt")
    private Instant createdAt;
    @Field("updatedAt")
    private Instant updatedAt;
    @Field("frozenAt")
    private Instant frozenAt;
    @Field("closedAt")
    private Instant closedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public AccountType getType() { return type; }
    public void setType(AccountType type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public StatusReason getStatusReason() { return statusReason; }
    public void setStatusReason(StatusReason statusReason) { this.statusReason = statusReason; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getFrozenAt() { return frozenAt; }
    public void setFrozenAt(Instant frozenAt) { this.frozenAt = frozenAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
