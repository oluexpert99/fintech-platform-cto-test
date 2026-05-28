package com.example.fintech.accounts.persistence.mapper;

import com.example.fintech.accounts.api.dto.AccountResponse;
import com.example.fintech.accounts.api.dto.BalanceResponse;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import org.springframework.stereotype.Component;

@Component
public class AccountMapper {
    public AccountResponse toResponse(AccountDocument doc) {
        return new AccountResponse(
                doc.getId(),
                doc.getOwnerUserId(),
                doc.getCurrency(),
                doc.getType(),
                doc.getLabel(),
                doc.getBalance(),
                doc.getStatus(),
                doc.getStatusReason(),
                doc.getVersion(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }

    public BalanceResponse toBalance(AccountDocument doc) {
        return new BalanceResponse(doc.getId(), doc.getBalance(), doc.getCurrency(), doc.getUpdatedAt());
    }
}
