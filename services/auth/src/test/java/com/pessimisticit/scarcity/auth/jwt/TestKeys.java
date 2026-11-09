package com.pessimisticit.scarcity.auth.jwt;

import java.security.KeyPair;
import java.util.Base64;

/** Test keys are generated at runtime; nothing is committed. */
public final class TestKeys {

    private static KeyPair rsa3072;

    private TestKeys() {
    }

    public static synchronized KeyPair rsa3072() {
        if (rsa3072 == null) {
            rsa3072 = RsaKeys.generate(3072);
        }
        return rsa3072;
    }

    public static String pkcs8Pem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }
}
