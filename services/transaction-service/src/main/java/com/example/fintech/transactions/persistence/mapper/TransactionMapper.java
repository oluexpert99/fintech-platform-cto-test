package com.example.fintech.transactions.persistence.mapper;

import com.example.fintech.transactions.api.dto.TransactionResponse;
import com.example.fintech.transactions.domain.model.TransactionStatus;
import com.example.fintech.transactions.domain.model.TransactionType;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapper from the persistence document to the wire DTO.
 * Hot-path conversion; we keep it explicit (not MapStruct) for readability + control.
 */
@Component
public class TransactionMapper {

    public TransactionResponse toResponse(TransactionDocument doc) {
        if (doc == null) {
            return null;
        }
        return new TransactionResponse(
                doc.getId(),
                doc.getType() != null ? TransactionType.valueOf(doc.getType()) : null,
                doc.getStatus() != null ? TransactionStatus.valueOf(doc.getStatus()) : null,
                doc.getSourceAccount(),
                doc.getDestinationAccount(),
                doc.getAmount(),
                doc.getCurrency(),
                doc.getDescription(),
                doc.getJournalLineIds(),
                doc.getCorrectsTransactionId(),
                doc.getReason(),
                doc.getApproverId(),
                doc.getCreatedAt(),
                doc.getCompletedAt());
    }
}
