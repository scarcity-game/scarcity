package com.pessimisticit.scarcity.resourcemodel.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pessimisticit.scarcity.resourcemodel.Page;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceIds;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

/**
 * The generic resource API (list by filter, list children, get one). No auth checks.
 * {@code size} above 200 is clamped to 200; {@code size} below 1, a negative {@code page},
 * an unknown type, a malformed id or a bad filter key give 400.
 */
@RestController
@RequestMapping(value = "${scarcity.resource-api.prefix:/resources}", produces = MediaType.APPLICATION_JSON_VALUE)
public class ResourceController {

    private static final Set<String> PAGING_PARAMS = Set.of("type", "page", "size");
    private static final int MAX_FILTERS = 16;

    private final ResourceStore store;

    public ResourceController(ResourceStore store) {
        this.store = store;
    }

    @GetMapping
    public Page<Resource> list(@RequestParam Map<String, String> params) {
        String type = params.get("type");
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        Map<String, String> filter = new LinkedHashMap<>();
        int[] filterCount = {0}; // Use array to make it effectively final
        params.forEach((k, v) -> {
            if (!PAGING_PARAMS.contains(k)) {
                filterCount[0]++;
                filter.put(k, v);
            }
        });
        if (filterCount[0] > MAX_FILTERS) {
            throw new IllegalArgumentException("at most 16 filter parameters");
        }
        return store.list(ResourceType.fromWire(type), filter, page(params), size(params));
    }

    @GetMapping("/{id}/children")
    public Page<Resource> children(@PathVariable String id, @RequestParam Map<String, String> params) {
        ResourceIds.typeOf(id);
        String type = params.get("type");
        ResourceType t = type == null || type.isEmpty() ? null : ResourceType.fromWire(type);
        int page = page(params);
        int size = size(params);
        if (!store.exists(id)) {
            throw new NotFound(id);
        }
        return store.children(id, t, page, size);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> get(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ResourceIds.typeOf(id);
        Resource r = store.get(id).orElseThrow(() -> new NotFound(id));
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
