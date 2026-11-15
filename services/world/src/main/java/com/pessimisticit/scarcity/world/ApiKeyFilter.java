package com.pessimisticit.scarcity.world;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-Api-Key} (root AGENTS.md, "Auth"), before the JWT
 * resource server. Without the header the request goes on to the JWT check
 * unchanged. With it: also an {@code Authorization} header, or the header
 * twice, is a 400; an unknown or empty key is a 401; both with an empty body.
 * A known key authenticates the request as its principal. The key is never
 * logged; a rejection logs the remote address, at most once per interval.
 */
final class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final ApiKeys keys;
    private final long logIntervalNanos;
    private final AtomicLong nextLog = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressed = new AtomicLong();
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    ApiKeyFilter(ApiKeys keys, Duration logInterval) {
        super();
        this.keys = keys;
        this.logIntervalNanos = logInterval.toNanos();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> offered = Collections.list(request.getHeaders(HEADER));
        if (offered.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (offered.size() > 1 || request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        Optional<String> principal = keys.principalFor(offered.get(0));
        if (principal.isEmpty()) {
            logRejection(request);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        LOG.debug("api-key accepted for principal {}", principal.get());
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthentication(principal.get()));
        contexts.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contexts.clearContext();
        }
    }

    private void logRejection(HttpServletRequest request) {
        long now = System.nanoTime();
        long next = nextLog.get();
        boolean tooSoon = next != Long.MIN_VALUE && now - next < 0;
        if (tooSoon || !nextLog.compareAndSet(next, now + logIntervalNanos)) {
            suppressed.incrementAndGet();
            return;
        }
        LOG.warn("api-key rejected from {} ({} more since the last report)",
                request.getRemoteAddr(), suppressed.getAndSet(0));
    }
}
