package com.pessimisticit.scarcity.auth.user;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Username and password rules.
 *
 * <p>Lengths are counted in Unicode code points, not UTF-16 units, so an emoji counts as one
 * character. Passwords are additionally limited to 72 UTF-8 bytes because bcrypt ignores
 * everything after byte 72; such passwords are rejected rather than silently truncated. In
 * practice that byte limit is the binding one: 72 ASCII characters, fewer for non-ASCII.
 */
public final class CredentialsPolicy {

    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 32;
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 128;
    public static final int PASSWORD_MAX_BYTES = 72;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_-]*");

    private CredentialsPolicy() {
    }

    public static List<FieldError> checkUsername(String username) {
        List<FieldError> errors = new ArrayList<>();
        if (username == null) {
            errors.add(new FieldError("username", "is required"));
            return errors;
        }
        int length = username.codePointCount(0, username.length());
        if (length < USERNAME_MIN || length > USERNAME_MAX) {
            errors.add(new FieldError("username",
                    "must be " + USERNAME_MIN + "-" + USERNAME_MAX + " characters long"));
        }
        if (!USERNAME.matcher(username).matches()) {
            errors.add(new FieldError("username", "may only contain A-Z, a-z, 0-9, '_' and '-'"));
        }
        return errors;
    }

    public static List<FieldError> checkPassword(String password) {
        List<FieldError> errors = new ArrayList<>();
        if (password == null) {
            errors.add(new FieldError("password", "is required"));
            return errors;
        }
        if (password.codePoints().anyMatch(cp -> Character.getType(cp) == Character.SURROGATE)) {
            // An unpaired surrogate (e.g. JSON "\ud800") has no UTF-8 encoding.
            errors.add(new FieldError("password", "contains invalid Unicode (unpaired surrogate)"));
            return errors;
        }
        int length = password.codePointCount(0, password.length());
        if (length < PASSWORD_MIN || length > PASSWORD_MAX) {
            errors.add(new FieldError("password",
                    "must be " + PASSWORD_MIN + "-" + PASSWORD_MAX + " characters long"));
        }
        if (utf8Length(password) > PASSWORD_MAX_BYTES) {
            errors.add(new FieldError("password",
                    "must be at most " + PASSWORD_MAX_BYTES + " bytes when UTF-8 encoded (bcrypt limit)"));
        }
        return errors;
    }

    public static List<FieldError> checkRegistration(String username, String password) {
        List<FieldError> errors = new ArrayList<>(checkUsername(username));
        errors.addAll(checkPassword(password));
        return errors;
    }

    static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** True if bcrypt can hash this password without truncation (and without throwing). */
    static boolean fitsBcrypt(String password) {
        return utf8Length(password) <= PASSWORD_MAX_BYTES;
    }

    public record FieldError(String field, String message) {
    }
}
