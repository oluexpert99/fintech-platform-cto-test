package com.example.fintech.gateway.filter;

import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Generates or propagates {@code X-Correlation-Id} on every request, sets it on MDC for
 * structured logs, and echoes it on the response.
 *
 * <p>Highest precedence so every downstream filter sees the correlation id on MDC.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String existing = req.getHeaders().getFirst(HEADER);
        String correlationId = (existing == null || existing.isBlank())
                ? UlidCreator.getUlid().toString()
                : existing;

        ServerHttpRequest mutated = req.mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();

        // Echo the correlation id on the response via beforeCommit. By the time this global
        // filter runs for a proxied route the response headers are read-only, so a direct
        // set(...) throws UnsupportedOperationException — which, surfacing after the response is
        // committed, closed the connection and truncated chunked downstream bodies. beforeCommit
        // runs while the headers are still mutable, and is simply skipped if already committed.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        MDC.put("correlationId", correlationId);
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(s -> MDC.remove("correlationId"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
