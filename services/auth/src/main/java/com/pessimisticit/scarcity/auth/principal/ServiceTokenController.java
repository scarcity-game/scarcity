package com.pessimisticit.scarcity.auth.principal;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pessimisticit.scarcity.auth.AuthProperties;
import com.pessimisticit.scarcity.auth.jwt.TokenService;
import com.pessimisticit.scarcity.auth.jwt.TokenService.IssuedToken;

/**
 * {@code POST /api/auth/service-token}: a service principal exchanges its API key
 * ({@code X-Api-Key}) for a short-lived JWT it uses on REST and on the subscriptions
 * WebSocket (root AGENTS.md, "Auth"). A missing and an unknown key get the same 401.
 */
@RestController
@RequestMapping("/api/auth")
public class ServiceTokenController {

    static final String HEADER = "X-Api-Key";

    private final ServiceKeys keys;
    private final TokenService tokens;
    private final Duration ttl;

    public ServiceTokenController(ServiceKeys keys, TokenService tokens, AuthProperties properties) {
        this.keys = keys;
        this.tokens = tokens;
        this.ttl = properties.serviceToken().ttl();
    }

    @PostMapping("/service-token")
    public ResponseEntity<?> exchange(@RequestHeader(name = HEADER, required = false) String key) {
        Optional<String> principal = key == null ? Optional.empty() : keys.principalFor(key);
        if (principal.isEmpty()) {
            ServiceAudit.refused();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "ApiKey")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid service key"));
        }
        IssuedToken token = tokens.mintService(principal.get(), ttl);
        ServiceAudit.issued(principal.get());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ServiceTokenResponse(token.token(), ttl.toSeconds()));
    }

    /** {@code expiresIn} is the token's lifetime in seconds, as in RFC 6749's token response. */
    public record ServiceTokenResponse(String token, long expiresIn) {
    }
}
