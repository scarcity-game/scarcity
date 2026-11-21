package com.pessimisticit.scarcity.resourcemodel.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;

/**
 * The body of {@code GET {prefix}/{id}/versions}: the versions of the resource and everything under it,
 * their checksum as 8 hex digits, and the area's {@code logSeq} (left out when there is none).
 */
@JsonPropertyOrder({"id", "versions", "checksum", "logSeq"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Versions(String id, Map<String, Long> versions, String checksum, Long logSeq) {
}
