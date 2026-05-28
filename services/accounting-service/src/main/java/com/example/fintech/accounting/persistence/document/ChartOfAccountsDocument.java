package com.example.fintech.accounting.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One row in the Chart of Accounts. See {@code specs/accounting-service.spec.md} §3.4.
 *
 * <p>The {@code _id} is the account number (e.g. {@code "1000"}, {@code "2100"}). User-wallet
 * leaves like {@code "2100.<accountId>"} are <em>not</em> stored here — they're computed refs
 * on journal entries; the parent {@code "2100"} row covers the entire user-wallet bucket.
 */
@Document(collection = "chart_of_accounts")
public class ChartOfAccountsDocument {

    @Id private String id;
    @Field("name") private String name;
    @Field("type") private String type;             // ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE
    @Field("normalSide") private String normalSide; // DEBIT | CREDIT
    @Field("parentId") private String parentId;
    @Field("system") private boolean system;
    @Field("currency") private String currency;
    @Field("createdAt") private Instant createdAt;
    @Field("updatedAt") private Instant updatedAt;

    public ChartOfAccountsDocument() {}

    public ChartOfAccountsDocument(String id, String name, String type, String normalSide,
                                    String parentId, boolean system, String currency, Instant now) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.normalSide = normalSide;
        this.parentId = parentId;
        this.system = system;
        this.currency = currency;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getNormalSide() { return normalSide; }
    public String getParentId() { return parentId; }
    public boolean isSystem() { return system; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
