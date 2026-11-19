package com.pessimisticit.scarcity.auth.web;

import java.sql.SQLTransientConnectionException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Tells "the database cannot be reached" from every other failure. Only connection-level
 * failures count; constraint violations and other data access errors are real bugs (500).
 */
final class DatabaseUnavailable {

    private DatabaseUnavailable() {
    }

    /**
     * True if the exception or any cause is one the pool or the transaction manager throws when no
     * connection can be had: Spring's {@link DataAccessResourceFailureException} (which includes
     * {@code CannotGetJdbcConnectionException}), {@link CannotCreateTransactionException}, or Hikari's
     * {@link SQLTransientConnectionException}.
     */
    static boolean causedBy(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof SQLTransientConnectionException) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }
}
