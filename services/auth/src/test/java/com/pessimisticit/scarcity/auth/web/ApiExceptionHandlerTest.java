package com.pessimisticit.scarcity.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import com.pessimisticit.scarcity.auth.AuthProperties;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(new AuthProperties(null, null, null,
            new AuthProperties.Unavailable(Duration.ofSeconds(3))));

    @Test
    void connectionFailuresAre503WithRetryAfter() {
        SQLTransientConnectionException hikari = new SQLTransientConnectionException("pool timeout");
        for (Exception e : new Exception[] {
                new CannotGetJdbcConnectionException("no connection", hikari),
                new DataAccessResourceFailureException("down"),
                new CannotCreateTransactionException("no tx", hikari),
                new RuntimeException("wrapped", hikari)}) {
            ResponseEntity<ProblemDetail> r = handler.unexpected(e);
            assertThat(r.getStatusCode().value()).as(e.toString()).isEqualTo(503);
            assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("3");
            assertThat(r.getBody().getStatus()).isEqualTo(503);
        }
    }

    @Test
    void constraintViolationsAndOtherBugsStay500() {
        for (Exception e : new Exception[] {
                new DataIntegrityViolationException("not null violated"),
                new QueryTimeoutException("slow query"),
                new IllegalStateException("bug")}) {
            ResponseEntity<ProblemDetail> r = handler.unexpected(e);
            assertThat(r.getStatusCode().value()).as(e.toString()).isEqualTo(500);
            assertThat(r.getHeaders().containsKey("Retry-After")).isFalse();
        }
    }
}
