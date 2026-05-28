package com.example.fintech.accounts.domain.exception;

import java.util.Map;

public final class ForbiddenFieldEditException extends DomainException {
    private final String field;

    public ForbiddenFieldEditException(String field) {
        super("Forbidden field edit: " + field);
        this.field = field;
    }

    @Override
    public String code() {
        return "FORBIDDEN";
    }

    @Override
    public Map<String, Object> params() {
        return Map.of("field", field);
    }
}
