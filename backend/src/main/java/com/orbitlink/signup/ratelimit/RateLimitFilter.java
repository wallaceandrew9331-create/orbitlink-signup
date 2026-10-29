package com.orbitlink.signup.ratelimit;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orbitlink.signup.OrbitlinkProperties;
import com.orbitlink.signup.ratelimit.TokenBucketLimiter.Decision;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Per-IP token bucket in front of the public API. The per-account bucket lives in the order controller. */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketLimiter limiter;
    private final boolean trustForwardedFor;

    public RateLimitFilter(TokenBucketLimiter limiter, OrbitlinkProperties props) {
        this.limiter = limiter;
        this.trustForwardedFor = props.rateLimit().trustForwardedFor();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Decision d = limiter.tryAcquireForIp(clientIp(request));
        if (!d.allowed()) {
            RateLimitResponses.write(response, d.retryAfterMillis(), "too many requests from this address");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The left-most X-Forwarded-For entry is whatever the client claims, so it is only used when
     * the deployment says a trusted proxy sets it (nginx in docker compose does).
     */
    String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
