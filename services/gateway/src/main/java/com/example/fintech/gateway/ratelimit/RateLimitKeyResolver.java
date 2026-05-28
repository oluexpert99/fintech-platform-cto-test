package com.example.fintech.gateway.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the bucket(s) that apply to a request.
 *
 * <p>See {@code gateway.spec.md} §4.2:
 * <ul>
 *   <li>An IP bucket ({@code anon:<ipApprox>}) is evaluated for every request.</li>
 *   <li>If the request carries a Bearer JWT, an additional user-route bucket
 *       ({@code user:<sub>:<route>:<METHOD>}) is evaluated.</li>
 * </ul>
 *
 * <p>This runs <em>pre-security</em> in a {@link org.springframework.web.server.WebFilter},
 * before Spring Cloud Gateway has matched a route — so the route id is derived from the
 * URL path prefix rather than {@code ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR}. The mapping
 * here mirrors the route table in {@code application.yaml} §3.1; keep them in sync.
 *
 * <p>The JWT is decoded without signature verification — the downstream
 * {@code JwtAuthenticationFilter} rejects bad tokens, so the worst an attacker can
 * do here is land in a bucket they don't own (their request will still fail at 401).
 */
@Component
public class RateLimitKeyResolver {

    private static final Pattern IPV4 = Pattern.compile("^(\\d+\\.\\d+\\.\\d+)\\.\\d+$");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    public String anonKey(ServerWebExchange exchange) {
        return "anon:" + ipApprox(exchange);
    }

    /** Returns the user-route bucket key, or empty if the request is unauthenticated. */
    public Optional<String> userKey(ServerWebExchange exchange) {
        String sub = extractSub(exchange.getRequest()).orElse(null);
        if (sub == null) {
            return Optional.empty();
        }
        String routeId = routeIdFromPath(exchange.getRequest().getPath().value());
        String method = exchange.getRequest().getMethod().name();
        return Optional.of("user:" + sub + ":" + routeId + ":" + method);
    }

    /** Picks the bucket config that applies to a given user-key route+method. */
    public RateLimitProperties.Bucket userBucket(ServerWebExchange exchange,
                                                 RateLimitProperties.Defaults defaults) {
        String routeId = routeIdFromPath(exchange.getRequest().getPath().value());
        String method = exchange.getRequest().getMethod().name();
        if ("transactions".equals(routeId) && "POST".equals(method)) {
            return defaults.getUserTransactionsWrite();
        }
        if ("auth-sessions".equals(routeId) && "POST".equals(method)) {
            return defaults.getUserSessionsPost();
        }
        return defaults.getUserDefault();
    }

    /**
     * Path → route id, mirroring {@code application.yaml} §3.1. Kept package-private so the
     * filter and tests can reuse it.
     */
    public static String routeIdFromPath(String path) {
        if (path.startsWith("/v1/users"))             return "auth-users";
        if (path.startsWith("/v1/sessions"))          return "auth-sessions";
        if (path.startsWith("/v1/oauth/"))            return "auth-oauth";
        if (path.startsWith("/v1/accounts"))          return "accounts";
        if (path.startsWith("/v1/transactions"))      return "transactions";
        if (path.startsWith("/v1/journal-entries"))   return "journal-entries";
        if (path.startsWith("/v1/reports"))           return "reports";
        if (path.startsWith("/v1/chart-of-accounts")) return "chart-of-accounts";
        return "unrouted";
    }

    private static String ipApprox(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String xff = headers.getFirst("X-Forwarded-For");
        String ip;
        if (xff != null && !xff.isBlank()) {
            ip = xff.split(",", 2)[0].trim();
        } else {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            ip = remote != null ? remote.getAddress().getHostAddress() : "unknown";
        }
        var m = IPV4.matcher(ip);
        return m.matches() ? m.group(1) + ".0/24" : ip;
    }

    private static Optional<String> extractSub(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = auth.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] payload = B64.decode(parts[1]);
            JsonNode node = JSON.readTree(payload);
            JsonNode sub = node.get("sub");
            return sub == null || sub.isNull() ? Optional.empty() : Optional.of(sub.asString());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
