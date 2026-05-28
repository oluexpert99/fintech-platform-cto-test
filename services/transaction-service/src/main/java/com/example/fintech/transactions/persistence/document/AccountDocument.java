package com.example.fintech.transactions.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Mirror of the {@code accounts} document owned by Account Service (see {@code data-model.spec} §5.2).
 *
 * <p>Transaction Service writes only {@code balance}, {@code updatedAt}, and {@code version} —
 * enforced by the {@code fintech_journal_writer} role + validator. Account Service writes the rest.
 *
 * <p>We use a class (not a record) because Spring Data MongoDB's optimistic-locking machinery
 * relies on mutating {@code @Version}.
 */
@Document(collection = "accounts")
public class AccountDocument {

    @Id
    private String id;

    @Field("ownerUserId")
    private String ownerUserId;

    @Field("currency")
    private String currency;

    @Field("type")
    private String type;

    @Field("label")
    private String label;

    @Field("balance")
    private long balance;

    @Field("status")
    private String status;

    @Field("statusReason")
    private String statusReason;

    @Field("frozenAt")
    private Instant frozenAt;

    @Field("closedAt")
    private Instant closedAt;

    @Field("createdAt")
    private Instant createdAt;

    @Field("updatedAt")
    private Instant updatedAt;

    @Version
    private Long version;

    public AccountDocument() {
        // required by Spring Data
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public long getBalance() { return balance; }
    public void setBalance(long balance) { this.balance = balance; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Instant getFrozenAt() { return frozenAt; }
    public void setFrozenAt(Instant frozenAt) { this.frozenAt = frozenAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
