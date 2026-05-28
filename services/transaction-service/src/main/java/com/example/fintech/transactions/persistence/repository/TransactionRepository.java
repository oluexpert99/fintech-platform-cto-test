package com.example.fintech.transactions.persistence.repository;

import com.example.fintech.transactions.persistence.document.TransactionDocument;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends MongoRepository<TransactionDocument, String> {

    Optional<TransactionDocument> findByIdempotencyKey(String idempotencyKey);

    /**
     * Whether a REVERSAL transaction has already been posted against this original.
     * Used to derive REVERSED state without mutating the original record — preserves audit history.
     */
    boolean existsByCorrectsTransactionId(String correctsTransactionId);

    /**
     * Keyset-paginated list: first page (no cursor).
     * ULID-based {@code _id} is time-sortable, so DESC order ≡ newest first.
     */
    List<TransactionDocument> findByCallerSubOrderByIdDesc(String callerSub, Limit limit);

    /**
     * Keyset-paginated list: subsequent page anchored at the cursor's afterId.
     */
    List<TransactionDocument> findByCallerSubAndIdLessThanOrderByIdDesc(
            String callerSub, String afterId, Limit limit);
}
