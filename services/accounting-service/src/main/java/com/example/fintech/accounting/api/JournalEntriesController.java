package com.example.fintech.accounting.api;

import com.example.fintech.accounting.api.dto.JournalEntryResponse;
import com.example.fintech.accounting.api.dto.PagedResponse;
import com.example.fintech.accounting.application.JournalFinder;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/journal-entries", produces = MediaType.APPLICATION_JSON_VALUE)
public class JournalEntriesController {

    private final JournalFinder finder;

    public JournalEntriesController(JournalFinder finder) {
        this.finder = finder;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('SCOPE_admin:*', 'ROLE_auditor', 'ROLE_operator')")
    public PagedResponse<JournalEntryResponse> list(
            @RequestParam(name = "account",       required = false) String account,
            @RequestParam(name = "transactionId", required = false) String transactionId,
            @RequestParam(name = "cursor",        required = false) String cursor,
            @RequestParam(name = "limit",         required = false) Integer limit) {
        return finder.list(account, transactionId, cursor, limit);
    }
}
