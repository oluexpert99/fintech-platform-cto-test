package com.example.fintech.auth.domain.exception;

import java.util.Map;

public sealed abstract class DomainException extends RuntimeException
        permits AccountLockedException,
                EmailAlreadyRegisteredException,
                InvalidCredentialsException,
                MfaInvalidException,
                MfaRequiredException,
                MissingIdempotencyKeyException,
                RefreshTokenRevokedException,
                StepUpRequiredException,
                UserNotFoundException,
                WeakPasswordException {

    protected DomainException(String message) { super(message); }
    protected DomainException(String message, Throwable cause) { super(message, cause); }

    public abstract String code();
    public abstract Map<String, Object> params();
}
