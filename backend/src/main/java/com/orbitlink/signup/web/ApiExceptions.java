package com.orbitlink.signup.web;

public final class ApiExceptions {

    private ApiExceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class RateLimitedException extends RuntimeException {
        private final long retryAfterMillis;

        public RateLimitedException(long retryAfterMillis, String message) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }
    }
}
