package com.example.fintech.transactions.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * The canonical record of a transfer or reversal. See {@code data-model.spec} §5.3.
 */
@Document(collection = "transactions")
public class TransactionDocument {

    @Id
    private String id;

    /** Scoped key: sha256(userId + "|" + endpoint + "|" + clientKey). Unique index enforces idempotency. */
    @Indexed(unique = true)
    @Field("idempotencyKey")
    private String idempotencyKey;

    /** SHA-256 of the canonical JSON of the request payload — used for replay match. */
    @Field("payloadHash")
    private String payloadHash;

    @Field("type")
    private String type;

    @Field("status")
    private String status;

    @Field("sourceAccount")
    private String sourceAccount;

    @Field("destinationAccount")
    private String destinationAccount;

    @Field("amount")
    private long amount;

    @Field("currency")
    private String currency;

    @Field("description")
    private String description;

    @Field("journalLineIds")
    private List<String> journalLineIds;

    @Field("correctsTransactionId")
    private String correctsTransactionId;

    @Field("reason")
    private String reason;

    @Field("approverId")
    private String approverId;

    /** The JWT {@code sub} that initiated this transaction. */
    @Field("callerSub")
    private String callerSub;

    @Field("createdAt")
    private Instant createdAt;

    @Field("completedAt")
    private Instant completedAt;

    @Version
    private Long version;

    public TransactionDocument() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSourceAccount() { return sourceAccount; }
    public void setSourceAccount(String sourceAccount) { this.sourceAccount = sourceAccount; }

    public String getDestinationAccount() { return destinationAccount; }
    public void setDestinationAccount(String destinationAccount) { this.destinationAccount = destinationAccount; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getJournalLineIds() { return journalLineIds; }
    public void setJournalLineIds(List<String> journalLineIds) { this.journalLineIds = journalLineIds; }

    public String getCorrectsTransactionId() { return correctsTransactionId; }
    public void setCorrectsTransactionId(String correctsTransactionId) { this.correctsTransactionId = correctsTransactionId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getApproverId() { return approverId; }
    public void setApproverId(String approverId) { this.approverId = approverId; }

    public String getCallerSub() { return callerSub; }
    public void setCallerSub(String callerSub) { this.callerSub = callerSub; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
