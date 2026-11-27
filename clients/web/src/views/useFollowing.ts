import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import type { LogEntry } from '../log/types.ts';
import type { Resource } from '../resources/cache.ts';
import { areaPath } from '../resources/routeIds.ts';

/** How long a follower waits for the log to say where the entity went, then takes it to be gone. */
const LOST_MS = 10_000;

/** The entity this spectator follows (browser state, never game state), if any. Off by default. */
export interface Following {
  followedId: string | null;
  /** Follows `id`; null stops following. */
  follow: (id: string) => void;
  stop: () => void;
}

/** The spectator's following, and the move to the followed entity's new area when it leaves this one. */
export function useFollowing(where: Where): Following {
  const [followedId, setFollowedId] = useState<string | null>(null);
  const follow = useCallback((id: string) => setFollowedId(id), []);
  const stop = useCallback(() => setFollowedId(null), []);
  useFollowAcross({ ...where, followedId, onLost: stop });
  return { followedId, follow, stop };
}

function touches(entry: LogEntry, entityId: string): boolean {
  return (
    entry.resource === entityId ||
    entry.loggedEffect?.params?.entity === entityId ||
    entry.changes.some((c) => c.id === entityId)
  );
}

/**
 * The area `entityId` left this one for, from the `depart` entry of its crossing (its `result`
 * names `toArea`, docs/action-types.md): undefined unless the newest entry that touches the entity
 * is that departure. A later entry (it came back, and moves here) makes an older departure stale.
 */
export function departedTo(entries: readonly LogEntry[], entityId: string): string | undefined {
  const newest = newestTouching(entries, entityId);
  return newest && departureArea(newest);
}

function newestTouching(entries: readonly LogEntry[], entityId: string): LogEntry | undefined {
  let newest: LogEntry | undefined;
  for (const entry of entries) {
    if ((newest === undefined || entry.seq > newest.seq) && touches(entry, entityId))
      newest = entry;
  }
  return newest;
}

/** The `toArea` of a `depart` effect entry; undefined for any other entry. */
function departureArea(entry: LogEntry): string | undefined {
  if (entry.kind !== 'effect' || entry.loggedEffect?.type !== 'depart') return undefined;
  const toArea = (entry.result as { toArea?: unknown } | undefined)?.toArea;
  return typeof toArea === 'string' && toArea !== '' ? toArea : undefined;
}

function holds(resources: ReadonlyMap<string, Resource>, areaId: string, entityId: string | null) {
  return entityId !== null && (resources.get(areaId)?.children.includes(entityId) ?? false);
}

/** The area page: what it holds now, and its log. */
interface Where {
  resources: ReadonlyMap<string, Resource>;
  logEntries: readonly LogEntry[];
  areaId: string;
  gameId: string;
}

interface FollowAcross extends Where {
  followedId: string | null;
  /** The entity left this area, and the log names no area it went to. */
  onLost: () => void;
}

/**
 * A follower goes where the followed entity goes: when it leaves this area (stairs) the spectator
 * moves to the area its `depart` log entry names, and follows on there; the entity may still be
 * between areas, and turns up when it arrives. The entry comes after the change that removes the
 * entity, so it is awaited for {@link LOST_MS}. Without a followed entity nothing moves the
 * spectator, whoever leaves.
 */
export function useFollowAcross(props: FollowAcross): void {
  const { resources, logEntries, areaId, gameId, followedId, onLost } = props;
  const navigate = useNavigate();
  const here = holds(resources, areaId, followedId);
  // The area and entity this spectator has seen the entity in: it can only leave from there.
  const key = `${areaId}|${followedId}`;
  const [seenKey, setSeenKey] = useState<string | null>(null);
  if (here && seenKey !== key) setSeenKey(key);
  const gone = followedId !== null && !here && seenKey === key;
  const toArea = gone
    ? departedTo(
        logEntries.filter((e) => e.area === areaId),
        followedId,
      )
    : undefined;
  useEffect(() => {
    if (toArea === undefined || toArea === areaId) return;
    navigate(areaPath(gameId, toArea), { replace: true });
  }, [toArea, areaId, gameId, navigate]);
  useEffect(() => {
    if (!gone || toArea !== undefined) return;
    const timer = setTimeout(onLost, LOST_MS);
    return () => clearTimeout(timer);
  }, [gone, toArea, onLost]);
}
