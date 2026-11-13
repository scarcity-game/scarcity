package com.pessimisticit.scarcity.world;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Reads the area log (contract: {@code docs/log.md}, "Reading the log"). */
@RestController
public class LogController {

    private final AreaLog areaLog;

    public LogController(AreaLog areaLog) {
        this.areaLog = areaLog;
    }

    @GetMapping(path = "/api/world/log", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode log(@RequestParam Map<String, String> params) {
        String area = params.get("area");
        if (area == null || area.isEmpty()) {
            throw new IllegalArgumentException("area is required");
        }
        long after = longParam(params, "after", 0);
        if (after < 0) {
            throw new IllegalArgumentException("after must be >= 0");
        }
        long limit = longParam(params, "limit", 100);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        String kind = params.get("kind");
        if (kind != null && kind.isEmpty()) {
            kind = null;
        }
        return areaLog.read(area, after, (int) Math.min(limit, AreaLog.MAX_LIMIT), kind);
    }

    private static long longParam(Map<String, String> params, String name, long dflt) {
        String v = params.get(name);
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.getMessage() == null ? "Bad Request" : e.getMessage()));
    }
}
