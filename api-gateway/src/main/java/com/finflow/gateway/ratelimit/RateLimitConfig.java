package com.finflow.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    private static final String PUBLIC_AUTH_PREFIX = "/api/auth/";

    /**
     * Authenticated traffic is limited per user: JwtAuthenticationFilter (order -100) overwrites X-User-Id
     * from the verified token before route filters run, so the header can be trusted here.
     * Public auth endpoints (login/register/refresh) carry no verified identity, so they are limited per client IP;
     * this also stops a caller from dodging the limit by spoofing X-User-Id on those paths.
     */
    @Bean
    public KeyResolver clientKeyResolver() {
        return exchange -> {
            var request = exchange.getRequest();
            var userId = request.getHeaders().getFirst("X-User-Id");
            boolean publicAuthPath = request.getPath().value().startsWith(PUBLIC_AUTH_PREFIX);

            if (userId != null && !publicAuthPath) {
                return Mono.just("user:" + userId);
            }
            var remote = request.getRemoteAddress();
            var ip = remote != null && remote.getAddress() != null
                    ? remote.getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }
}
