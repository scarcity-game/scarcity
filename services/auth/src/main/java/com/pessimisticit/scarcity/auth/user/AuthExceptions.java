package com.pessimisticit.scarcity.auth.user;

import java.util.List;

/** Domain failures that the web layer maps to problem+json responses. */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /** 400. */
    public static final class InvalidRequestException extends RuntimeException {
        private final transient List<CredentialsPolicy.FieldError> errors;

        public InvalidRequestException(List<CredentialsPolicy.FieldError> errors) {
            super("Validation failed");
            this.errors = List.copyOf(errors);
        }

        public List<CredentialsPolicy.FieldError> errors() {
            return errors;
        }
    }

    /** 409. */
    public static final class UsernameTakenException extends RuntimeException {
        public UsernameTakenException() {
            super("Username is already taken");
        }
    }

    /** 401 from login. Deliberately carries no detail about why. */
    public static final class BadCredentialsException extends RuntimeException {
        public BadCredentialsException() {
            super("Invalid username or password");
        }
    }

    /** 401 from bearer-token endpoints. */
    public static final class InvalidTokenException extends RuntimeException {
        private final boolean missing;

        public InvalidTokenException(boolean missing) {
            super(missing ? "Bearer token required" : "Invalid or expired token");
            this.missing = missing;
        }

        public boolean missing() {
            return missing;
        }
    }
}
