package com.example.fintech.auth.domain.exception;

import java.util.Map;

public final class StepUpRequiredException extends DomainException {
    public StepUpRequiredException() { super("Step-up authentication required"); }
    @Override public String code() { return "STEP_UP_REQUIRED"; }
    @Override public Map<String, Object> params() { return Map.of(); }
}
