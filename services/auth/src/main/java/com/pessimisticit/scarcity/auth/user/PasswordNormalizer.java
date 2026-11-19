package com.pessimisticit.scarcity.auth.user;

import java.text.Normalizer;

/**
 * The one place passwords are Unicode-normalised (NFKC, NIST SP 800-63B; decision on
 * issue #7). {@link AuthService} normalises every submitted password with this before
 * validating, hashing or verifying it, so no path can skip it. Nothing else is done to the
 * password: no trimming, case folding or stripping. NFKC leaves ASCII unchanged.
 */
final class PasswordNormalizer {

    private PasswordNormalizer() {
    }

    static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }
}
