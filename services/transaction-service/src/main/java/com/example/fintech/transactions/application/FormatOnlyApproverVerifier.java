package com.example.fintech.transactions.application;

import com.example.fintech.transactions.domain.exception.OperatorApprovalRequiredException;
import com.example.fintech.transactions.domain.model.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Default (conservative) {@link ApproverVerifier}. Validates that:
 * <ol>
 *   <li>{@code approverId} is non-blank.</li>
 *   <li>{@code approverId} is well-formed (matches the platform's UserId pattern {@code U-<ulid>}).</li>
 *   <li>{@code approverId} is distinct from the caller.</li>
 * </ol>
 *
 * <p>It then logs the verification with both caller and approver IDs so the action is auditable
 * and bumps a counter — a non-zero rate of REVERSAL approvals is itself a security signal
 * (per ARCHITECTURE.md §10 incident playbooks).
 *
 * <p><strong>This is not a production verifier.</strong> It cannot attest that {@code approverId}
 * is a real user, that the user holds the operator role, or that the user actually consented to
 * this specific reversal. It is the minimum bar that prevents the trivial "any random string"
 * abuse path the reviewer flagged. Replace with a Keycloak-backed lookup or signed-token
 * verifier before production launch — see {@link ApproverVerifier} javadoc.
 */
@Component
public class FormatOnlyApproverVerifier implements ApproverVerifier {

    private static final Logger log = LoggerFactory.getLogger(FormatOnlyApproverVerifier.class);
    private static final Pattern USER_ID = Pattern.compile("^U-[0-9A-HJKMNP-TV-Z]{26}$");

    private final MeterRegistry meterRegistry;

    public FormatOnlyApproverVerifier(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void verify(UserId caller, String approverId) {
        if (approverId == null || approverId.isBlank()) {
            throw new OperatorApprovalRequiredException("Approver ID is required for dual-control");
        }
        if (!USER_ID.matcher(approverId).matches()) {
            throw new OperatorApprovalRequiredException(
                    "Approver ID is not a well-formed platform user identifier");
        }
        if (caller.value().equals(approverId)) {
            throw new OperatorApprovalRequiredException("Approver must differ from caller");
        }

        // Audit + metric. Non-zero rate is itself a security signal.
        meterRegistry.counter("transactions.reversal.approvals.total",
                "verifier", "format-only").increment();
        log.warn("REVERSAL approval recorded (FORMAT-ONLY verifier): caller={} approverId={}. "
                + "Approver identity is NOT cryptographically attested — replace this verifier "
                + "with a Keycloak admin lookup or signed approval token before production.",
                caller.value(), approverId);
    }
}
