package com.example.fintech.gateway.unit;

import com.example.fintech.gateway.ratelimit.RateLimitKeyResolver;
import com.example.fintech.gateway.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();

    @Test
    void anonKeyUsesIpv4Slash24() {
        ServerWebExchange exchange = exchangeWith("GET", "/v1/accounts", "203.0.113.45", null, "accounts");

        assertThat(resolver.anonKey(exchange)).isEqualTo("anon:203.0.113.0/24");
    }

    @Test
    void anonKeyFallsBackToXForwardedFor() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/v1/accounts")
                .header("X-Forwarded-For", "198.51.100.7, 10.0.0.1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("accounts").uri(URI.create("http://x")).predicate(e -> true).build());

        assertThat(resolver.anonKey(exchange)).isEqualTo("anon:198.51.100.0/24");
    }

    @Test
    void userKeyComposesSubRouteAndMethod() {
        String token = bearerWithSub("U-123");
        ServerWebExchange exchange = exchangeWith("POST", "/v1/transactions", "203.0.113.5", token, "transactions");

        assertThat(resolver.userKey(exchange)).contains("user:U-123:transactions:POST");
    }

    @Test
    void userKeyAbsentWithoutBearerToken() {
        ServerWebExchange exchange = exchangeWith("GET", "/v1/accounts", "203.0.113.5", null, "accounts");

        assertThat(resolver.userKey(exchange)).isEmpty();
    }

    @Test
    void userBucketPicksTransactionsWriteForPostOnTransactions() {
        RateLimitProperties.Defaults defaults = new RateLimitProperties().getDefaults();
        ServerWebExchange exchange = exchangeWith("POST", "/v1/transactions",
                "203.0.113.5", bearerWithSub("U-1"), "transactions");

        assertThat(resolver.userBucket(exchange, defaults)).isSameAs(defaults.getUserTransactionsWrite());
    }

    @Test
    void userBucketPicksSessionsPostForLogin() {
        RateLimitProperties.Defaults defaults = new RateLimitProperties().getDefaults();
        ServerWebExchange exchange = exchangeWith("POST", "/v1/sessions",
                "203.0.113.5", bearerWithSub("U-1"), "auth-sessions");

        assertThat(resolver.userBucket(exchange, defaults)).isSameAs(defaults.getUserSessionsPost());
    }

    @Test
    void userBucketFallsBackToUserDefault() {
        RateLimitProperties.Defaults defaults = new RateLimitProperties().getDefaults();
        ServerWebExchange exchange = exchangeWith("GET", "/v1/accounts",
                "203.0.113.5", bearerWithSub("U-1"), "accounts");

        assertThat(resolver.userBucket(exchange, defaults)).isSameAs(defaults.getUserDefault());
    }

    private static ServerWebExchange exchangeWith(String method, String path, String remoteIp,
                                                  String bearer, String routeId) {
        MockServerHttpRequest.BaseBuilder<?> builder = switch (method) {
            case "GET" -> MockServerHttpRequest.get(path);
            case "POST" -> MockServerHttpRequest.post(path);
            default -> throw new IllegalArgumentException(method);
        };
        builder.remoteAddress(new java.net.InetSocketAddress(remoteIp, 12345));
        if (bearer != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri(URI.create("http://x")).predicate(e -> true).build());
        return exchange;
    }

    private static String bearerWithSub(String sub) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = enc.encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes());
        return header + "." + payload + ".sig";
    }
}
