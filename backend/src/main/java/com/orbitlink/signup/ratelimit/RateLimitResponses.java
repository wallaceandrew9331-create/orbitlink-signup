package com.orbitlink.signup.ratelimit;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/** 429 in RFC 9457 problem+json shape, matching what the exception handler returns elsewhere. */
public final class RateLimitResponses {

    private RateLimitResponses() {}

    public static long retryAfterSeconds(long retryAfterMillis) {
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }

    static void write(HttpServletResponse response, long retryAfterMillis, String detail) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds(retryAfterMillis)));
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,"detail":"%s"}""".formatted(detail));
    }
}
