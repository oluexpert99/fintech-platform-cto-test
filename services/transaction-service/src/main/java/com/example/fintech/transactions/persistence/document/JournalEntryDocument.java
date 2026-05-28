package com.example.fintech.transactions.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One side of a double-entry posting. Immutable — the {@code fintech_journal_writer} Mongo role
 * has {@code insert} but not {@code update} or {@code remove} privileges on this collection
 * (see {@code data-model.spec} §5.4).
 */
@Document(collection = "journal")
public class JournalEntryDocument {

    @Id
    private String id;

    @Field("transactionId")
    private String transactionId;

    @Field("account")
    private String account;

    /**
     * Formal Chart-of-Accounts reference. For TRANSFER/REVERSAL lines on a user wallet this is
     * {@code "2100." + account} (user wallets are leaf sub-accounts of {@code 2100 Customer
     * Wallet Liability}). For system-account lines (future: FEE, BAD_DEBT) it's a top-level COA
     * number like {@code "4000"}, and {@code account} is null.
     * See {@code specs/accounting-service.spec.md} §3.4.
     */
    @Field("coaAccount")
    private String coaAccount;

    @Field("side")
    private String side;

    @Field("amount")
    private long amount;

    @Field("currency")
    private String currency;

    @Field("postedAt")
    private Instant postedAt;

    public JournalEntryDocument() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getCoaAccount() { return coaAccount; }
    public void setCoaAccount(String coaAccount) { this.coaAccount = coaAccount; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
}
