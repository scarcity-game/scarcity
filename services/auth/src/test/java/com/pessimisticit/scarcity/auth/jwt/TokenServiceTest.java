package com.pessimisticit.scarcity.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00.750Z");
    private static final UUID USER = UUID.fromString("6f1c1d2e-0000-4000-8000-000000000001");

    private final KeyPair keys = TestKeys.rsa3072();
    private final TokenService service = at(NOW);

    private TokenService at(Instant now) {
        return new TokenService(keys, Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void mintedTokenHasExactlyTheContractClaims() throws Exception {
        TokenService.IssuedToken issued = service.mint(USER, "Alice");
        SignedJWT jwt = SignedJWT.parse(issued.token());

        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(service.keyId());

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "username", "iat", "exp");
        assertThat(claims.getIssuer()).isEqualTo("scarcity-auth");
        assertThat(claims.getSubject()).isEqualTo(USER.toString());
        assertThat(claims.getStringClaim("username")).isEqualTo("Alice");
        Instant iat = Instant.parse("2026-11-09T10:00:00Z");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(iat);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(iat.plus(Duration.ofHours(1)));
        assertThat(issued.expiresAt()).isEqualTo(iat.plus(Duration.ofHours(1)));
    }

    @Test
    void kidIsRfc7638Thumbprint() throws Exception {
        String expected = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).build().computeThumbprint().toString();
        assertThat(service.keyId()).isEqualTo(expected);
    }

    @Test
    void verifiesItsOwnToken() {
        String token = service.mint(USER, "Alice").token();
        assertThat(service.verify(token)).contains(new TokenService.VerifiedToken(USER, "Alice"));
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.mint(USER, "Alice").token();
        assertThat(at(NOW.plus(Duration.ofMinutes(59))).verify(token)).isPresent();
        assertThat(at(NOW.plus(Duration.ofHours(1)).plus(TokenService.CLOCK_SKEW).plusSeconds(1)).verify(token))
                .isEmpty();
    }

    @Test
    void rejectsTokenIssuedInTheFuture() {
        String token = service.mint(USER, "Alice", NOW.plus(Duration.ofMinutes(5))).token();
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsTokenFromAnotherKey() {
        TokenService other = new TokenService(RsaKeys.generate(2048), Duration.ofHours(1),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(service.verify(other.mint(USER, "Alice").token())).isEmpty();
    }

    @Test
    void rejectsForgedTokenWithOurKidButOtherKey() throws Exception {
        KeyPair attacker = RsaKeys.generate(2048);
        String token = sign((RSAPrivateKey) attacker.getPrivate(), service.keyId(), claims("scarcity-auth"));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        String token = sign((RSAPrivateKey) keys.getPrivate(), service.keyId(), claims("someone-else"));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void acceptsHandSignedTokenWithCorrectClaims() throws Exception {
        String token = sign((RSAPrivateKey) keys.getPrivate(), service.keyId(), claims("scarcity-auth"));
        assertThat(service.verify(token)).isPresent();
    }

    @Test
    void rejectsMissingClaims() throws Exception {
        JWTClaimsSet noUsername = new JWTClaimsSet.Builder()
                .issuer("scarcity-auth").subject(USER.toString())
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(60))).build();
        assertThat(service.verify(sign((RSAPrivateKey) keys.getPrivate(), service.keyId(), noUsername))).isEmpty();

        JWTClaimsSet noExp = new JWTClaimsSet.Builder()
                .issuer("scarcity-auth").subject(USER.toString()).claim("username", "Alice")
                .issueTime(Date.from(NOW)).build();
        assertThat(service.verify(sign((RSAPrivateKey) keys.getPrivate(), service.keyId(), noExp))).isEmpty();

        JWTClaimsSet badSub = new JWTClaimsSet.Builder()
                .issuer("scarcity-auth").subject("not-a-uuid").claim("username", "Alice")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(60))).build();
        assertThat(service.verify(sign((RSAPrivateKey) keys.getPrivate(), service.keyId(), badSub))).isEmpty();
    }

    @Test
    void rejectsWrongKid() throws Exception {
        String token = sign((RSAPrivateKey) keys.getPrivate(), "other-kid", claims("scarcity-auth"));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsAlgNoneAndHmac() throws Exception {
        assertThat(service.verify(new PlainJWT(claims("scarcity-auth")).serialize())).isEmpty();

        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(service.keyId()).build(),
                claims("scarcity-auth"));
        hs.sign(new MACSigner(new byte[32]));
        assertThat(service.verify(hs.serialize())).isEmpty();
    }

    @Test
    void rejectsTamperedAndGarbage() {
        String token = service.mint(USER, "Alice").token();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
        assertThat(service.verify(tampered)).isEmpty();
        assertThat(service.verify("garbage")).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify(null)).isEmpty();
    }

    @Test
    void jwksContainsOnlyPublicParameters() throws Exception {
        Map<String, Object> json = service.publicJwks();
        JWKSet set = JWKSet.parse(json);
        assertThat(set.getKeys()).hasSize(1);
        RSAKey key = (RSAKey) set.getKeys().getFirst();
        assertThat(key.isPrivate()).isFalse();
        assertThat(key.getKeyID()).isEqualTo(service.keyId());
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);

        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((java.util.List<Map<String, Object>>) json.get("keys")).getFirst();
        assertThat(jwk.keySet()).containsExactlyInAnyOrder("kid", "kty", "alg", "use", "n", "e");
    }

    private static JWTClaimsSet claims(String issuer) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(USER.toString())
                .claim("username", "Alice")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(3600)))
                .build();
    }

    private static String sign(RSAPrivateKey key, String kid, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
