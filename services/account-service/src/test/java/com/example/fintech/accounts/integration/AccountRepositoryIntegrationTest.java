package com.example.fintech.accounts.integration;

import com.example.fintech.accounts.domain.model.AccountStatus;
import com.example.fintech.accounts.domain.model.AccountType;
import com.example.fintech.accounts.domain.model.StatusReason;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void resetCollections() {
        mongoTemplate.dropCollection(AccountDocument.class);
    }

    @Test
    void findByIdWithMajority_returnsDocument() {
        AccountDocument account = seedAccount("ACCINT001", 3L);
        assertThat(accountRepository.findByIdWithMajority(account.getId())).isPresent();
    }

    @Test
    void updateIfVersionMatches_updatesOnlyWhenExpectedVersionMatches() {
        AccountDocument account = seedAccount("ACCINT002", 5L);
        account.setLabel("next");
        account.setVersion(6L);
        account.setUpdatedAt(Instant.now());

        assertThat(accountRepository.updateIfVersionMatches(account, 5L)).isPresent();
        assertThat(accountRepository.updateIfVersionMatches(account, 5L)).isEmpty();
    }

    private AccountDocument seedAccount(String id, long version) {
        AccountDocument doc = new AccountDocument();
        doc.setId(id);
        doc.setOwnerUserId("U-ALICE");
        doc.setCurrency("USD");
        doc.setType(AccountType.CHECKING);
        doc.setLabel("main");
        doc.setBalance(0L);
        doc.setStatus(AccountStatus.ACTIVE);
        doc.setStatusReason(StatusReason.USER_REQUESTED);
        doc.setVersion(version);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return accountRepository.save(doc);
    }
}
