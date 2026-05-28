package com.example.fintech.accounting.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A row in accounting-service's own {@code journal_projection} collection.
 *
 * <p>This is <strong>not</strong> a read-through view of transaction-service's {@code journal}
 * collection. It's a materialised projection in accounting-service's own database
 * ({@code fintech_accounting}), fed by {@link com.example.fintech.accounting.messaging.TransactionEventsProjector}
 * consuming {@code transactions.transfer.completed} and {@code transactions.transfer.reversed}.
 *
 * <p>This eliminates the shared-collection coupling between transaction-service and
 * accounting-service: each owns its own database. Reads here cannot slow down the write path,
 * and the projection can evolve independently of transaction-service's schema.
 *
 * <p>Eventual consistency: typical lag is &lt;1s in healthy clusters. The {@code asOf} parameter
 * on report endpoints already accepts this.
 */
@Document(collection = "journal_projection")
public class JournalEntryDocument {

    /**
     * Deterministically derived from the source event's {@code transactionId} + side
     * ({@code JL-PROJ-<txId-without-prefix>-<DR|CR>}) so that re-projection of a duplicate Kafka
     * delivery is a Mongo upsert with no side effects. Source events don't carry journal line
     * ids; the projector synthesises this id locally.
     */
    @Id
    private String id;

    @Field("transactionId") private String transactionId;
    @Field("transactionType") private String transactionType;  // TRANSFER | REVERSAL | FEE | ...
    @Field("account") private String account;                   // user wallet id, when applicable
    @Field("coaAccount") private String coaAccount;             // formal COA reference
    @Field("side") private String side;                         // DEBIT | CREDIT
    @Field("amount") private long amount;
    @Field("currency") private String currency;
    @Field("postedAt") private Instant postedAt;
    /** When the projector saw this entry — for projection-lag observability. */
    @Field("projectedAt") private Instant projectedAt;

    public JournalEntryDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getCoaAccount() {
        if (coaAccount != null && !coaAccount.isBlank()) return coaAccount;
        // Legacy fallback for events written before coaAccount was on the wire.
        if (account != null && !account.isBlank()) return "2100." + account;
        return null;
    }
    public void setCoaAccount(String coaAccount) { this.coaAccount = coaAccount; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }

    public Instant getProjectedAt() { return projectedAt; }
    public void setProjectedAt(Instant projectedAt) { this.projectedAt = projectedAt; }
}
