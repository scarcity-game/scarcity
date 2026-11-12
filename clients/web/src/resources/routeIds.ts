// Browser paths show the bare uuid; everything else (REST, WebSocket, cache)
// uses the full type-prefixed resource id (`game:<uuid>`). This is the only
// place that converts between the two and builds /games/... paths.

/** `game:<uuid>` -> `<uuid>` (the path segment). Ids without a prefix pass through. */
export function idToSegment(id: string): string {
  const i = id.indexOf(':');
  return i < 0 ? id : id.slice(i + 1);
}

/**
 * Path segment -> resource id of the given type. A bare uuid gets the prefix; an
 * old-style segment that already carries the right prefix is kept as is. A
 * segment with another type's prefix (or an empty one) returns null, never a
 * valid-looking id of the wrong type.
 */
export function segmentToId(type: string, segment: string | undefined): string | null {
  if (!segment) return null;
  const prefix = `${type}:`;
  if (segment.startsWith(prefix)) return segment.length > prefix.length ? segment : null;
  return segment.includes(':') ? null : prefix + segment;
}

export function gamePath(gameId: string): string {
  return `/games/${encodeURIComponent(idToSegment(gameId))}`;
}

export function areaPath(gameId: string, areaId: string): string {
  return `${gamePath(gameId)}/areas/${encodeURIComponent(idToSegment(areaId))}`;
}
