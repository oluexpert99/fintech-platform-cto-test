package com.example.fintech.accounting.application;

import com.example.fintech.accounting.api.dto.JournalEntryResponse;
import com.example.fintech.accounting.api.dto.PagedResponse;
import com.example.fintech.accounting.persistence.document.JournalEntryDocument;
import com.example.fintech.accounting.persistence.repository.JournalEntryRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JournalFinder {

    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 25;

    private final JournalEntryRepository repository;

    public JournalFinder(JournalEntryRepository repository) {
        this.repository = repository;
    }

    public PagedResponse<JournalEntryResponse> list(String account, String transactionId,
                                                     String cursorParam, Integer limitParam) {
        int limit = clampLimit(limitParam);
        Cursor cursor = Cursor.decode(cursorParam);
        Limit fetchLimit = Limit.of(limit + 1);

        List<JournalEntryDocument> rows;
        if (transactionId != null && !transactionId.isBlank()) {
            // transactionId filter — small fixed result; cursor optional but ignored.
            rows = repository.findByTransactionIdOrderByIdDesc(transactionId, fetchLimit);
        } else if (account != null && !account.isBlank()) {
            rows = cursor == null
                    ? repository.findByAccountOrderByIdDesc(account, fetchLimit)
                    : repository.findByAccountAndIdLessThanOrderByIdDesc(account, cursor.afterId(), fetchLimit);
        } else {
            rows = cursor == null
                    ? repository.findAllByOrderByIdDesc(fetchLimit)
                    : repository.findByIdLessThanOrderByIdDesc(cursor.afterId(), fetchLimit);
        }

        boolean hasMore = rows.size() > limit;
        List<JournalEntryDocument> page = hasMore ? rows.subList(0, limit) : rows;
        List<JournalEntryResponse> data = page.stream().map(JournalFinder::toResponse).toList();
        String nextCursor = (hasMore && !page.isEmpty())
                ? Cursor.of(page.get(page.size() - 1).getId()).encode()
                : null;

        return PagedResponse.of(data, nextCursor, hasMore, limit);
    }

    private static JournalEntryResponse toResponse(JournalEntryDocument d) {
        return new JournalEntryResponse(
                d.getId(), d.getTransactionId(), d.getAccount(), d.getCoaAccount(),
                d.getSide(), d.getAmount(), d.getCurrency(), d.getPostedAt());
    }

    private static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }
}
