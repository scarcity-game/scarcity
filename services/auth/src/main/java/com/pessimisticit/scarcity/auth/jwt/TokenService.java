package com.pessimisticit.scarcity.auth.jwt;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Mints and verifies the service's RS256 JWTs.
 *
 * <p>Claims are exactly {@code iss=scarcity-auth}, {@code sub=<user uuid>}, {@code username},
 * {@code iat}, {@code exp}. The {@code kid} header is the RFC 7638 SHA-256 thumbprint of the
 * public key.
 */
public class TokenService {

    public static final String ISSUER = "scarcity-auth";
    public static final String USERNAME_CLAIM = "username";
    /** Allowed clock difference when checking {@code exp} and {@code iat}. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private final RSAKey publicJwk;
    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(KeyPair keyPair, Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        try {
            RSAKey bare = new RSAKey.Builder(publicKey).build();
            this.publicJwk = new RSAKey.Builder(publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(bare.computeThumbprint().toString())
                    .build();
            this.signer = new RSASSASigner((RSAPrivateKey) keyPair.getPrivate());
            this.verifier = new RSASSAVerifier(publicKey);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot set up JWT signing", e);
        }
    }

    public String keyId() {
        return publicJwk.getKeyID();
    }

    /** JWKS containing only public parameters. */
    public Map<String, Object> publicJwks() {
        return new JWKSet(publicJwk.toPublicJWK()).toJSONObject(true);
    }

    public IssuedToken mint(UUID userId, String username) {
        return mint(userId, username, clock.instant());
    }

    /** Visible for tests that need tokens issued at a given time (e.g. already expired). */
    public IssuedToken mint(UUID userId, String username, Instant issuedAt) {
        Instant iat = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        Instant exp = iat.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(USERNAME_CLAIM, username)
                .issueTime(Date.from(iat))
                .expirationTime(Date.from(exp))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(keyId())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign JWT", e);
        }
        return new IssuedToken(jwt.serialize(), exp);
    }

    /**
     * Verifies signature, algorithm, key id, issuer and lifetime.
     *
     * @return the token's subject and username, or empty if the token is not valid
     */
    public Optional<VerifiedToken> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || !keyId().equals(header.getKeyID())) {
                return Optional.empty();
            }
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            Date exp = claims.getExpirationTime();
            Date iat = claims.getIssueTime();
            String username = claims.getStringClaim(USERNAME_CLAIM);
            if (!ISSUER.equals(claims.getIssuer())
                    || claims.getSubject() == null
                    || username == null
                    || exp == null
                    || iat == null
                    || !exp.toInstant().plus(CLOCK_SKEW).isAfter(now)
                    || iat.toInstant().minus(CLOCK_SKEW).isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(UUID.fromString(claims.getSubject()), username));
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record VerifiedToken(UUID userId, String username) {
    }
}
