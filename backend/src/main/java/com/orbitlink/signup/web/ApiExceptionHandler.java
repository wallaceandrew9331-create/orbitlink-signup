package com.orbitlink.signup.web;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.orbitlink.signup.orders.OrderService.AlreadyOrderedException;
import com.orbitlink.signup.orders.OrderService.CapacityContentionException;
import com.orbitlink.signup.orders.OrderService.IdempotencyKeyReusedException;
import com.orbitlink.signup.ratelimit.RateLimitResponses;
import com.orbitlink.signup.web.ApiExceptions.NotFoundException;
import com.orbitlink.signup.web.ApiExceptions.RateLimitedException;

/** Maps domain exceptions to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body failed validation");
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParameter(HandlerMethodValidationException e) {
        Map<String, String> errors = e.getParameterValidationResults().stream()
                .collect(Collectors.toMap(
                        r -> r.getMethodParameter().getParameterName() == null ? "?" : r.getMethodParameter().getParameterName(),
                        r -> r.getResolvableErrors().getFirst().getDefaultMessage(), (a, b) -> a));
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request parameters failed validation");
        p.setProperty("errors", errors);
        return p;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "missing header " + e.getHeaderName());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    /** DuplicateKeyException is the unique-index backstop firing when two requests race past the pre-checks. */
    @ExceptionHandler({AlreadyOrderedException.class, DuplicateKeyException.class})
    ProblemDetail alreadyOrdered(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "this email already has an order or waitlist spot in this cell");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CapacityContentionException.class)
    ResponseEntity<ProblemDetail> contention(CapacityContentionException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(RateLimitResponses.retryAfterSeconds(e.retryAfterMillis())))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }
}
