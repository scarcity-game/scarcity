import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { findEntityArea } from '../resources/followedArea.ts';
import { areaPath } from '../resources/routeIds.ts';
import type { Request } from '../resources/worldApi.ts';

const RETRY_MS = 2000;
const MAX_ATTEMPTS = 15; // 30 s, then the entity is taken to be gone

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

function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => clearTimeout(timer), { once: true });
  });
}

interface Chase {
  request: Request;
  gameId: string;
  entityId: string;
  /** The area the entity left. */
  fromAreaId: string;
  signal: AbortSignal;
}

/** The area the entity has arrived in, looking again while it is between areas; undefined if it never does. */
async function findArrival(chase: Chase): Promise<Resource | undefined> {
  const { request, gameId, entityId, fromAreaId, signal } = chase;
  for (let attempt = 0; attempt < MAX_ATTEMPTS && !signal.aborted; attempt++) {
    const area = await findEntityArea(request, gameId, entityId, signal).catch(() => undefined);
    if (area && area.id !== fromAreaId) return area;
    await pause(RETRY_MS, signal);
  }
  return undefined;
}

function holds(resources: ReadonlyMap<string, Resource>, areaId: string, entityId: string | null) {
  return entityId !== null && (resources.get(areaId)?.children.includes(entityId) ?? false);
}

/** The area page: what it holds now. */
interface Where {
  resources: ReadonlyMap<string, Resource>;
  areaId: string;
  gameId: string;
}

interface FollowAcross extends Where {
  followedId: string | null;
  /** The entity could not be found in any area. */
  onLost: () => void;
}

/**
 * A follower goes where the followed entity goes: when it leaves this area (stairs) the spectator
 * looks for its new area by id (it may be between areas) and moves there, and follows on. Without a
 * followed entity nothing moves the spectator, whoever leaves.
 */
export function useFollowAcross(props: FollowAcross): void {
  const { resources, areaId, gameId, followedId, onLost } = props;
  const { authRequest } = useAuth();
  const navigate = useNavigate();
  const wasHere = useRef(false);
  const here = holds(resources, areaId, followedId);
  useEffect(() => {
    wasHere.current = false;
  }, [areaId, followedId]);
  useEffect(() => {
    if (followedId === null) return;
    if (here) {
      wasHere.current = true;
      return;
    }
    if (!wasHere.current) return;
    const controller = new AbortController();
    const { signal } = controller;
    findArrival({ request: authRequest, gameId, entityId: followedId, fromAreaId: areaId, signal })
      .then((area) => {
        if (signal.aborted) return;
        if (area) navigate(areaPath(gameId, area.id), { replace: true });
        else onLost();
      })
      .catch(() => {});
    return () => controller.abort();
  }, [here, followedId, areaId, gameId, authRequest, navigate, onLost]);
}
