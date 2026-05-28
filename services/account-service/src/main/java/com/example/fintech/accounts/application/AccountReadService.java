package com.example.fintech.accounts.application;

import com.example.fintech.accounts.api.dto.AccountResponse;
import com.example.fintech.accounts.api.dto.BalanceResponse;
import com.example.fintech.accounts.api.dto.PagedResponse;
import com.example.fintech.accounts.domain.exception.AccountNotFoundException;
import com.example.fintech.accounts.domain.exception.ForbiddenFieldEditException;
import com.example.fintech.accounts.domain.model.AccountId;
import com.example.fintech.accounts.persistence.document.AccountDocument;
import com.example.fintech.accounts.persistence.mapper.AccountMapper;
import com.example.fintech.accounts.persistence.repository.AccountRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AccountReadService {
    private final AccountRepository accountRepository;
    private final AccountMapper mapper;

    public AccountReadService(AccountRepository accountRepository, AccountMapper mapper) {
        this.accountRepository = accountRepository;
        this.mapper = mapper;
    }

    public AccountResponse get(String caller, Set<String> roles, AccountId accountId) {
        AccountDocument account = accountRepository.findById(accountId.value()).orElseThrow(() -> new AccountNotFoundException(accountId));
        authorize(caller, roles, account);
        return mapper.toResponse(account);
    }

    public PagedResponse<AccountResponse> list(String caller, int page, int size) {
        var data = accountRepository.findByOwnerUserIdOrderByCreatedAtDesc(caller, PageRequest.of(page, size));
        return new PagedResponse<>(data.getContent().stream().map(mapper::toResponse).toList(), page, size, data.getTotalElements());
    }

    public BalanceResponse balance(String caller, Set<String> roles, AccountId accountId) {
        AccountDocument account = accountRepository.findByIdWithMajority(accountId.value())
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        authorize(caller, roles, account);
        return mapper.toBalance(account);
    }

    private static void authorize(String caller, Set<String> roles, AccountDocument account) {
        if (!caller.equals(account.getOwnerUserId()) && !roles.contains("operator")) {
            throw new ForbiddenFieldEditException("account");
        }
    }
}
