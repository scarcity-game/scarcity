package com.pessimisticit.scarcity.auth.web;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.pessimisticit.scarcity.auth.user.AuthExceptions.BadCredentialsException;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.InvalidRequestException;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.InvalidTokenException;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.UsernameTakenException;

/**
 * Maps every error to RFC 7807 {@code application/problem+json}. The superclass covers
 * Spring MVC's own errors (malformed JSON, wrong content type, unknown path, wrong method).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Bad Request");
        List<Map<String, String>> errors = e.errors().stream()
                .map(err -> Map.of("field", err.field(), "message", err.message()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(UsernameTakenException.class)
    ProblemDetail taken(UsernameTakenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Username is already taken");
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail badCredentials(BadCredentialsException e) {
        // Must be byte-identical for unknown user and wrong password.
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    @ExceptionHandler(InvalidTokenException.class)
    ResponseEntity<ProblemDetail> invalidToken(InvalidTokenException e) {
        // RFC 6750 section 3.
        String challenge = e.missing() ? "Bearer" : "Bearer error=\"invalid_token\"";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }
}
