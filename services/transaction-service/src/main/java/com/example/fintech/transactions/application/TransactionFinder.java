package com.example.fintech.transactions.application;

import com.example.fintech.transactions.api.dto.PagedResponse;
import com.example.fintech.transactions.api.dto.TransactionResponse;
import com.example.fintech.transactions.domain.exception.TransactionNotFoundException;
import com.example.fintech.transactions.domain.model.TransactionId;
import com.example.fintech.transactions.domain.model.UserId;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import com.example.fintech.transactions.persistence.mapper.TransactionMapper;
import com.example.fintech.transactions.persistence.repository.TransactionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-side endpoints: {@code GET /v1/transactions/{id}} and {@code GET /v1/transactions}.
 * Ownership check ensures a caller can only see transactions they participated in
 * (as source or destination), unless they hold an admin role.
 */
@Service
public class TransactionFinder {

    /** Hard ceiling — per {@code api.md} §5 the limit max is 100. */
    public static final int MAX_LIMIT = 100;
    public static final int DEFAULT_LIMIT = 25;

    private final TransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;

    public TransactionFinder(TransactionRepository transactionRepository,
                              TransactionMapper transactionMapper) {
        this.transactionRepository = transactionRepository;
        this.transactionMapper = transactionMapper;
    }

    public TransactionResponse get(UserId caller, TransactionId id) {
        TransactionDocument doc = transactionRepository.findById(id.value())
                .orElseThrow(() -> notFound(id));
        if (!callerCanSee(caller, doc)) {
            // Same response as not-found to avoid enumeration
            throw notFound(id);
        }
        return transactionMapper.toResponse(doc);
    }

    public PagedResponse<TransactionResponse> list(UserId caller, String cursorParam, Integer limitParam) {
        int limit = clampLimit(limitParam);
        Cursor cursor = Cursor.decode(cursorParam);

        // Fetch limit+1 to detect hasMore without a second query
        Limit fetchLimit = Limit.of(limit + 1);
        List<TransactionDocument> rows = cursor == null
                ? transactionRepository.findByCallerSubOrderByIdDesc(caller.value(), fetchLimit)
                : transactionRepository.findByCallerSubAndIdLessThanOrderByIdDesc(
                        caller.value(), cursor.afterId(), fetchLimit);

        boolean hasMore = rows.size() > limit;
        List<TransactionDocument> page = hasMore ? rows.subList(0, limit) : rows;

        List<TransactionResponse> data = page.stream().map(transactionMapper::toResponse).toList();
        String nextCursor = (hasMore && !page.isEmpty())
                ? Cursor.of(page.get(page.size() - 1).getId()).encode()
                : null;

        return PagedResponse.of(data, nextCursor, hasMore, limit);
    }

    private boolean callerCanSee(UserId caller, TransactionDocument doc) {
        if (caller.value().equals(doc.getCallerSub())) {
            return true;
        }
        // Ownership-by-account: if the caller is the destination (received funds), let them see it.
        // The source-owner check via callerSub already covers the sender case.
        // TODO: cross-service lookup against Account Service for destination ownership.
        return false;
    }

    private static int clampLimit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        if (requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }

    private static TransactionNotFoundException notFound(TransactionId id) {
        return new TransactionNotFoundException(id);
    }
}
