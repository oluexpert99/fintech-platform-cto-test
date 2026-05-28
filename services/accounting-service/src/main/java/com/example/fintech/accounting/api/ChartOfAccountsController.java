package com.example.fintech.accounting.api;

import com.example.fintech.accounting.api.dto.ChartOfAccountsResponse;
import com.example.fintech.accounting.persistence.document.ChartOfAccountsDocument;
import com.example.fintech.accounting.persistence.repository.ChartOfAccountsRepository;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/v1/chart-of-accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChartOfAccountsController {

    private final ChartOfAccountsRepository repository;

    public ChartOfAccountsController(ChartOfAccountsRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_admin:*', 'ROLE_auditor', 'ROLE_operator')")
    public List<ChartOfAccountsResponse> list() {
        return repository.findAllByOrderByIdAsc().stream()
                .map(ChartOfAccountsController::toResponse)
                .toList();
    }

    private static ChartOfAccountsResponse toResponse(ChartOfAccountsDocument d) {
        return new ChartOfAccountsResponse(
                d.getId(), d.getName(), d.getType(), d.getNormalSide(),
                d.getParentId(), d.isSystem(), d.getCurrency(), d.getCreatedAt());
    }
}
