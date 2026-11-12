package com.pessimisticit.scarcity.world;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/** One real Postgres (as in prod) and one JWKS endpoint (as auth's) for the whole test JVM. */
final class TestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));
    static final RSAKey KEY;
    static final RSAKey OTHER_KEY;
    static final HttpServer JWKS;

    static {
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks.json", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            JWKS.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestSupport() {
    }

    static String jwksUri() {
        return "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json";
    }

    static String token(RSAKey signer, String issuer, long ttlMillis) {
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer)
                    .subject("00000000-0000-4000-8000-0000000000aa").claim("username", "tester")
                    .issueTime(new Date(now)).expirationTime(new Date(now + ttlMillis)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String validToken() {
        return token(KEY, "scarcity-auth", 3_600_000);
    }
}
