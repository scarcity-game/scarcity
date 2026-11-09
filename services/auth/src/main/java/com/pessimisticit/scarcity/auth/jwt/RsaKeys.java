package com.pessimisticit.scarcity.auth.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Loads RSA signing keys from PKCS#8 PEM, or generates ephemeral ones. Uses only JCA. */
public final class RsaKeys {

    /** Minimum accepted modulus size. Production keys are 3072 bits. */
    public static final int MIN_BITS = 2048;
    public static final int EPHEMERAL_BITS = 3072;

    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private RsaKeys() {
    }

    public static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate RSA key pair", e);
        }
    }

    /**
     * Reads a PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}) RSA private key and derives
     * the public key from its modulus and public exponent.
     *
     * @throws InvalidKeyFileException if the file is missing, unreadable or not a usable RSA key
     */
    public static KeyPair loadPkcs8Pem(Path path) {
        String pem;
        try {
            pem = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new InvalidKeyFileException("Cannot read JWT private key file " + path + ": " + e, e);
        }
        try {
            return fromPkcs8Pem(pem);
        } catch (InvalidKeyFileException e) {
            throw new InvalidKeyFileException("Invalid JWT private key file " + path + ": " + e.getMessage(), e);
        }
    }

    public static KeyPair fromPkcs8Pem(String pem) {
        int begin = pem.indexOf(BEGIN);
        int end = pem.indexOf(END);
        if (begin < 0 || end < begin) {
            if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
                throw new InvalidKeyFileException("PKCS#1 keys are not supported; convert with "
                        + "'openssl pkcs8 -topk8 -nocrypt' to get a '" + BEGIN + "' PEM");
            }
            throw new InvalidKeyFileException("expected a PKCS#8 PEM starting with '" + BEGIN + "'");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(pem.substring(begin + BEGIN.length(), end));
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyFileException("PEM body is not valid base64", e);
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new InvalidKeyFileException("RSA private key lacks CRT parameters; cannot derive public key");
            }
            int bits = crt.getModulus().bitLength();
            if (bits < MIN_BITS) {
                throw new InvalidKeyFileException("RSA key is " + bits + " bits; need at least " + MIN_BITS);
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(publicKey, crt);
        } catch (GeneralSecurityException e) {
            throw new InvalidKeyFileException("not a PKCS#8 RSA private key (" + e.getMessage() + ")", e);
        }
    }

    public static final class InvalidKeyFileException extends RuntimeException {
        InvalidKeyFileException(String message) {
            super(message);
        }

        InvalidKeyFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
