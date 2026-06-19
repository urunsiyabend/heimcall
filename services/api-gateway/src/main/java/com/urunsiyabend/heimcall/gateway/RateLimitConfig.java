package com.urunsiyabend.heimcall.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Phase 14 T1: rate limit the integration ingest route, keyed per integration so each integration is
 * throttled independently (a natural tenant boundary). The route path is
 * {@code /v1/integrations/{integrationKey}/events/{routingKey}}; the key is the third path segment.
 * Referenced from the route's RequestRateLimiter filter as {@code #{@integrationKeyResolver}}.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver integrationKeyResolver() {
        return exchange -> {
            String[] segments = exchange.getRequest().getURI().getPath().split("/");
            // "/v1/integrations/<integrationKey>/events/<routingKey>" -> ["", "v1", "integrations", key, ...]
            if (segments.length >= 4 && "v1".equals(segments[1]) && "integrations".equals(segments[2])) {
                return Mono.just(segments[3]);
            }
            // Not the ingest path (filter only sits on the integration route) — fall back to a single bucket.
            return Mono.just("unknown");
        };
    }
}
