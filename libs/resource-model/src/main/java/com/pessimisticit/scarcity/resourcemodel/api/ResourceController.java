package com.pessimisticit.scarcity.resourcemodel.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.pessimisticit.scarcity.resourcemodel.LogSeqSource;
import com.pessimisticit.scarcity.resourcemodel.VersionChecksum;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pessimisticit.scarcity.resourcemodel.Page;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;

/**
 * The generic resource API (list by filter, list children, get one). No auth checks.
 * {@code game=<game id>} limits a list to that game; {@code has=<key>} (repeatable) keeps resources whose JSON has that key non-null. {@code size} above 200 is clamped to 200; {@code size} below 1, a negative {@code page},
 * an unknown type, a malformed id or a bad filter key give 400.
 */
@RestController
@RequestMapping(value = "${scarcity.resource-api.prefix:/resources}", produces = MediaType.APPLICATION_JSON_VALUE)
public class ResourceController {

    private static final Set<String> PAGING_PARAMS = Set.of("type", "page", "size", "game", "child", "has");
    private static final int MAX_FILTERS = 16;

    private final ResourceStore resourceStore;
    private final LogSeqSource logSeqSource;

    /** @param logSeqSource the area log heads for {@code versions}; none: no {@code logSeq} in it */
    public ResourceController(ResourceStore resourceStore, ObjectProvider<LogSeqSource> logSeqSource) {
        this.resourceStore = resourceStore;
        this.logSeqSource = logSeqSource.getIfAvailable();
    }

    @GetMapping
    public Page<Resource> list(@RequestParam MultiValueMap<String, String> query) {
        Map<String, String> params = query.toSingleValueMap();
        List<String> has = query.getOrDefault("has", List.of());
        String type = params.get("type");
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        Map<String, String> filter = new LinkedHashMap<>(params);
        filter.keySet().removeAll(PAGING_PARAMS);
        if (has.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("has must not be empty");
        }
        if (filter.size() + has.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("at most " + MAX_FILTERS + " filter parameters");
        }
        return resourceStore.list(ResourceKind.of(type), params.get("game"), params.get("child"), filter, has,
                page(params), size(params));
    }

    @GetMapping("/{id}/children")
    public Page<Resource> children(@PathVariable String id, @RequestParam Map<String, String> params) {
        Ids.kindOf(id);
        String type = params.get("type");
        ResourceKind t = type == null || type.isEmpty() ? null : ResourceKind.of(type);
        int page = page(params);
        int size = size(params);
        if (!resourceStore.exists(id)) {
            throw new NotFound(id);
        }
        return resourceStore.children(id, t, page, size);
    }

    /**
     * The versions of {@code id} and everything under it, their checksum, and the area's {@code logSeq},
     * read in one snapshot so they agree.
     */
    @GetMapping("/{id}/versions")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Versions versions(@PathVariable String id) {
        Ids.kindOf(id);
        Map<String, Long> versions = resourceStore.versions(id);
        if (versions.isEmpty()) {
            throw new NotFound(id);
        }
        Long logSeq = null;
        if (logSeqSource != null) {
            logSeq = resourceStore.areaOf(id).stream()
                    .mapToLong(area -> logSeqSource.logSeq(area).orElse(-1)).filter(seq -> seq >= 0)
                    .boxed().findFirst().orElse(null);
        }
        return new Versions(id, versions, VersionChecksum.hex(VersionChecksum.of(versions)), logSeq);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> get(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Ids.kindOf(id);
        Resource r = resourceStore.get(id).orElseThrow(() -> new NotFound(id));
        String etag = "\"" + r.version() + "\"";
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(r);
    }

    private static boolean matches(String header, String etag) {
        if (header == null) {
            return false;
        }
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.equals("*") || p.equals(etag) || p.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private static int page(Map<String, String> params) {
        return intParam(params, "page", 0);
    }

    private static int size(Map<String, String> params) {
        int size = intParam(params, "size", 50);
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        return Math.min(size, ResourceStore.MAX_SIZE);
    }

    private static int intParam(Map<String, String> params, String name, int dflt) {
        String v = params.get(name);
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    static final class NotFound extends RuntimeException {
        NotFound(String id) {
            super("resource not found: " + id);
        }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
