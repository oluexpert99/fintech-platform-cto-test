package com.example.fintech.gateway.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts unhandled errors at the gateway (401 from JWT validation, 403 from access denial,
 * 404 from unmapped routes, 5xx from downstream failure) into RFC 7807 Problem Details with the
 * same {@code code} + {@code params} extension downstream services emit. The frontend handles
 * both gateway-level and service-level errors identically.
 *
 * <p>Order {@code HIGHEST_PRECEDENCE + 100} so we catch errors before Spring's default
 * whitelabel handler but after the correlation-id filter has set MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ProblemErrorWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorWebExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://api.example.com/problems/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatusCode status = statusOf(ex);
        String code = codeFor(status);
        String title = HttpStatus.valueOf(status.value()).getReasonPhrase();
        String detail = ex.getMessage() != null ? ex.getMessage() : title;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", PROBLEM_BASE + code.toLowerCase().replace('_', '-'));
        body.put("code", code);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("correlationId", MDC.get("correlationId"));
        body.put("timestamp", Instant.now().toString());

        if (status.is5xxServerError()) {
            log.error("gateway 5xx", ex);
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));

        DataBufferFactory factory = response.bufferFactory();
        DataBuffer buffer = factory.wrap(JSON.writeValueAsBytes(body));
        return response.writeWith(Mono.just(buffer));
    }

    private static HttpStatusCode statusOf(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String codeFor(HttpStatusCode status) {
        int s = status.value();
        return switch (s) {
            case 400 -> "MALFORMED_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "RATE_LIMITED";
            case 502, 503, 504 -> "DEPENDENCY_UNAVAILABLE";
            default -> s >= 500 ? "INTERNAL" : "BAD_REQUEST";
        };
    }
}
