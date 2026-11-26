package com.pessimisticit.scarcity.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * auth's JWKS on the JDK {@code HttpServer}, and tokens signed with its key as auth mints them
 * ({@code iss=scarcity-auth}, RS256), for tests of a service that verifies callers.
 */
public final class TestJwks implements AutoCloseable {

    private final RSAKey key;
    private final HttpServer server;

    public TestJwks() throws IOException {
        try {
            this.key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        server.start();
    }

    /** Where the keys are. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
    }

    /** world's service token: {@code sub=world, kind=service}. */
    public String worldToken() {
        return token("world", "service", Instant.now().plusSeconds(900));
    }

    /** A token as auth would sign it: {@code kind} null leaves the claim out (a user's token has none). */
    public String token(String subject, String kind, Instant expires) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("scarcity-auth")
                .subject(subject)
                .issueTime(new Date())
                .expirationTime(Date.from(expires));
        if (kind != null) {
            claims.claim("kind", kind);
        }
        return sign(claims.build(), key);
    }

    /** A token signed with a key auth doesn't publish. */
    public String forgedWorldToken() throws com.nimbusds.jose.JOSEException {
        RSAKey other = new RSAKeyGenerator(2048).keyID("test-key").generate();
        return sign(new JWTClaimsSet.Builder().issuer("scarcity-auth").subject("world").claim("kind", "service")
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).build(), other);
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
