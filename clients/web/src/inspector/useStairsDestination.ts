import { useEffect, useState } from 'react';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { findEntityArea } from '../resources/followedArea.ts';

/** Where a pair of stairs leads, as far as the client has found out. */
export type Destination =
  /** The paired stairs don't exist yet (`to` is null): no level there. */
  | { status: 'none' }
  | { status: 'loading' }
  | { status: 'found'; area: Resource }
  | { status: 'error'; message: string };

/** The stairs' `to`: the paired stairs' entity id, or null. */
export function pairedStairsOf(stairs: Resource): string | null {
  return typeof stairs.to === 'string' && stairs.to !== '' ? stairs.to : null;
}

/**
 * The area on the other side of `stairs`: the game's area whose children hold the paired stairs
 * (a resource has no parent key, `docs/resources.md`). Replace the lookup with the stairs' own
 * `toArea` once they carry it (#235).
 */
export function useStairsDestination(stairs: Resource, gameId: string): Destination {
  const { authRequest } = useAuth();
  const to = pairedStairsOf(stairs);
  const [state, setState] = useState<{ key: string; result: Destination } | null>(null);
  const key = `${gameId}|${to}`;
  useEffect(() => {
    if (to === null) return;
    const controller = new AbortController();
    findEntityArea(authRequest, gameId, to, controller.signal)
      .then((area): Destination =>
        area
          ? { status: 'found', area }
          : { status: 'error', message: 'The level these stairs lead to was not found.' },
      )
      .catch((error: unknown): Destination | null =>
        isAbortError(error) ? null : { status: 'error', message: errorMessage(error) },
      )
      .then((result) => {
        if (result) setState({ key, result });
      });
    return () => controller.abort();
  }, [authRequest, gameId, to, key]);
  if (to === null) return { status: 'none' };
  return state?.key === key ? state.result : { status: 'loading' };
}
