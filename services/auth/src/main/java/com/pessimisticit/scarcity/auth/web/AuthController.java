package com.pessimisticit.scarcity.auth.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pessimisticit.scarcity.auth.jwt.TokenService;
import com.pessimisticit.scarcity.auth.jwt.TokenService.IssuedToken;
import com.pessimisticit.scarcity.auth.user.AuthService;
import com.pessimisticit.scarcity.auth.user.User;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final TokenService tokens;

    public AuthController(AuthService auth, TokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@RequestBody Credentials body) {
        return UserResponse.of(auth.register(body.username(), body.password()));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> login(@RequestBody Credentials body) {
        IssuedToken token = auth.login(body.username(), body.password());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenResponse(token.token(), "Bearer", token.expiresAt()));
    }

    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse me(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return UserResponse.of(auth.currentUser(bearerToken(authorization)));
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(tokens.publicJwks());
    }

    /** Returns the token from "Bearer &lt;token&gt;" (scheme case-insensitive), or null if absent. */
    static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.strip();
        if (value.length() > 7 && value.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
            return value.substring(7).strip();
        }
        return ""; // present but not a Bearer credential: treated as an invalid token
    }

    /** Request body for register and login. */
    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=***]";
        }
    }

    /** {@code createdAt} is an ISO-8601 UTC instant, e.g. {@code 2026-11-09T10:15:30.123456Z}. */
    public record UserResponse(UUID id, String username, Instant createdAt) {
        static UserResponse of(User user) {
            return new UserResponse(user.id(), user.username(), user.createdAt());
        }
    }

    /** {@code expiresAt} is an ISO-8601 UTC instant with whole seconds (equal to the JWT's exp). */
    public record TokenResponse(String token, String tokenType, Instant expiresAt) {
    }
}
