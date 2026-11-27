import { useEffect, useState } from 'react';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { getResource } from '../resources/worldApi.ts';

/** Where a pair of stairs leads, as far as the client has found out. */
export type Destination =
  /** The paired stairs don't exist yet (`toArea` is null): no level there. */
  | { status: 'none' }
  | { status: 'loading' }
  | { status: 'found'; area: Resource }
  | { status: 'error'; message: string };

/** The stairs' `toArea`: the id of the area that holds the paired stairs, or null (#235). */
export function destinationAreaOf(stairs: Resource): string | null {
  return typeof stairs.toArea === 'string' && stairs.toArea !== '' ? stairs.toArea : null;
}

/** The area on the other side of `stairs`, read by the id the stairs carry: one request. */
export function useStairsDestination(stairs: Resource): Destination {
  const { authRequest } = useAuth();
  const toArea = destinationAreaOf(stairs);
  const [state, setState] = useState<{ key: string; result: Destination } | null>(null);
  useEffect(() => {
    if (toArea === null) return;
    const controller = new AbortController();
    getResource(authRequest, toArea, { signal: controller.signal })
      .then((area): Destination =>
        area
          ? { status: 'found', area }
          : { status: 'error', message: 'The level these stairs lead to was not found.' },
      )
      .catch((error: unknown): Destination | null =>
        isAbortError(error) ? null : { status: 'error', message: errorMessage(error) },
      )
      .then((result) => {
        if (result) setState({ key: toArea, result });
      });
    return () => controller.abort();
  }, [authRequest, toArea]);
  if (toArea === null) return { status: 'none' };
  return state?.key === toArea ? state.result : { status: 'loading' };
}
