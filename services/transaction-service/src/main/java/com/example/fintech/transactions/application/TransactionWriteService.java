package com.example.fintech.transactions.application;

import com.example.fintech.transactions.api.dto.CreateTransactionRequest;
import com.example.fintech.transactions.api.dto.TransactionResponse;
import com.example.fintech.transactions.domain.exception.IdempotencyConflictException;
import com.example.fintech.transactions.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.transactions.domain.exception.SelfTransferException;
import com.example.fintech.transactions.domain.model.AccountId;
import com.example.fintech.transactions.domain.model.UserId;
import com.example.fintech.transactions.persistence.document.TransactionDocument;
import com.example.fintech.transactions.persistence.mapper.TransactionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Orchestrator for {@code POST /v1/transactions}. Handles pre-transactional fail-fast checks
 * (self-transfer, dual-control) and the idempotency fast-path lookup, then delegates to
 * {@link TransactionExecutor} for the actual {@code @Transactional} write set.
 *
 * <p><strong>Design note:</strong> the {@code @Transactional} executor lives in a separate bean.
 * Calling it from this class crosses the Spring proxy boundary, which is what makes
 * {@code @Transactional} actually intercept. (A previous version had the executor as a
 * {@code protected} method on this class and the {@code @Transactional} was silently disabled by
 * self-invocation.)
 */
@Service
public class TransactionWriteService {

    private static final Logger log = LoggerFactory.getLogger(TransactionWriteService.class);
    private static final String OPERATION_TRANSFER = "transfer";
    private static final String OPERATION_REVERSAL = "reverse";
    private static final String ROLE_OPERATOR = "operator";

    private final TransactionExecutor executor;
    private final IdempotencyService idempotencyService;
    private final TransactionMapper transactionMapper;
    private final ApproverVerifier approverVerifier;

    public TransactionWriteService(
            TransactionExecutor executor,
            IdempotencyService idempotencyService,
            TransactionMapper transactionMapper,
            ApproverVerifier approverVerifier) {
        this.executor = executor;
        this.idempotencyService = idempotencyService;
        this.transactionMapper = transactionMapper;
        this.approverVerifier = approverVerifier;
    }

    /** Single chokepoint for POST /v1/transactions. Dispatches on {@code type}. */
    public TransactionResponse create(UserId caller,
                                      Set<String> callerRoles,
                                      String idempotencyKey,
                                      CreateTransactionRequest request) {
        return switch (request.type()) {
            case TRANSFER -> transfer(caller, idempotencyKey, request);
            case REVERSAL -> reverse(caller, callerRoles, idempotencyKey, request);
            default -> throw new UnsupportedOperationException(
                    "TransactionType not supported on this endpoint: " + request.type());
        };
    }

    // ============================================================================================
    // TRANSFER orchestration
    // ============================================================================================

    private TransactionResponse transfer(UserId caller, String idempotencyKey, CreateTransactionRequest request) {
        AccountId source = AccountId.of(request.sourceAccount());
        AccountId destination = AccountId.of(request.destinationAccount());
        if (source.equals(destination)) {
            throw new SelfTransferException(source);
        }
        // NOTE: per ADR-0006 + scope-decision #6, MFA is out of scope for the test deliverable.
        // The old step-up check is intentionally removed; reintroduce when the JWT's `acr` claim
        // freshness is wired through the gateway.

        Idempotent idempotent = lookupIdempotent(caller, OPERATION_TRANSFER, idempotencyKey, request);
        if (idempotent.replay != null) {
            log.debug("idempotent replay for key={} returning original tx={}",
                    idempotent.scopedKey, idempotent.replay.transactionId());
            return idempotent.replay;
        }

        return executor.executeTransfer(caller, idempotent.scopedKey, idempotent.payloadHash, source, destination, request);
    }

    // ============================================================================================
    // REVERSAL orchestration
    // ============================================================================================

    private TransactionResponse reverse(UserId caller, Set<String> callerRoles,
                                        String idempotencyKey, CreateTransactionRequest request) {
        if (callerRoles == null || !callerRoles.contains(ROLE_OPERATOR)) {
            throw new OperatorApprovalRequiredException("Operator role required for REVERSAL");
        }
        // Delegates approver identity verification — see ApproverVerifier javadoc. The default
        // FormatOnlyApproverVerifier validates the approverId format + audits the action, but
        // does not cryptographically attest the approver's identity. Production replacement is
        // a Keycloak admin lookup or signed approval token.
        approverVerifier.verify(caller, request.approverId());

        Idempotent idempotent = lookupIdempotent(caller, OPERATION_REVERSAL, idempotencyKey, request);
        if (idempotent.replay != null) {
            return idempotent.replay;
        }
        return executor.executeReversal(caller, idempotent.scopedKey, idempotent.payloadHash, request);
    }

    // ============================================================================================
    // Shared helpers
    // ============================================================================================

    private Idempotent lookupIdempotent(UserId caller, String operation, String idempotencyKey,
                                        CreateTransactionRequest request) {
        String scopedKey = idempotencyService.scopedKey(caller, operation, idempotencyKey);
        String payloadHash = idempotencyService.payloadHash(request);
        Optional<TransactionDocument> existing = idempotencyService.findExisting(scopedKey);
        if (existing.isPresent()) {
            TransactionDocument doc = existing.get();
            if (idempotencyService.payloadsMatch(doc, payloadHash)) {
                return new Idempotent(scopedKey, payloadHash, transactionMapper.toResponse(doc));
            }
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return new Idempotent(scopedKey, payloadHash, null);
    }

    /** Tuple carrying the scoped idempotency key, payload hash, and an optional replay response. */
    private record Idempotent(String scopedKey, String payloadHash, TransactionResponse replay) { }
}
