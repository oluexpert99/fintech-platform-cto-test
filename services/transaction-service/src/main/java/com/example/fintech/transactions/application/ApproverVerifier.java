package com.example.fintech.transactions.application;

import com.example.fintech.transactions.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.transactions.domain.model.UserId;

/**
 * Verifies a dual-control approver's identity before a REVERSAL proceeds.
 *
 * <p>This is the abstraction the reviewer flagged as missing. The default implementation
 * ({@link FormatOnlyApproverVerifier}) is conservative — it validates the approverId format
 * + audits the verification call but does <strong>not</strong> cryptographically attest the
 * approver's identity. A production implementation MUST either:
 *
 * <ol>
 *   <li>Call Auth Service synchronously to resolve {@code approverId} → user with {@code operator}
 *       role, OR</li>
 *   <li>Require a short-lived <em>signed approval token</em> (JWT) issued by the approver via a
 *       separate flow; the reversing operator includes the token in the request and we validate
 *       the signature, freshness, and that the token's subject matches {@code approverId}.</li>
 * </ol>
 *
 * <p>Option (2) is the canonical fintech pattern — it removes the synchronous Auth Service
 * dependency from the money path and provides cryptographic proof of consent. Pursue this when
 * Auth Service exposes an approval-token endpoint.
 *
 * <p>This abstraction also allows test code to inject a deterministic verifier rather than
 * standing up a real Auth Service.
 */
public interface ApproverVerifier {

    /**
     * Verifies the approver is distinct from the caller, well-formed, and (in production
     * implementations) actually exists with the operator role.
     *
     * @throws OperatorApprovalRequiredException if any check fails; the message identifies the
     *         failed gate so an audit trail records the reason.
     */
    void verify(UserId caller, String approverId);
}
