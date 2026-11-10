// Route changes, recorded in the main bundle and turned into spans by the
// lazily loaded SDK (sdk.ts). Changes that happen before the SDK has loaded
// (the first route, at least) are buffered and replayed with their original
// timestamps, so nothing from @opentelemetry is needed in the main bundle.

export interface RouteChange {
  /** The matched route pattern, e.g. `/play` or `/*`. Never a raw URL. */
  route: string;
  /** When the change was committed, in epoch milliseconds. */
  time: number;
}

type Listener = (change: RouteChange) => void;

const MAX_PENDING = 20;
let pending: RouteChange[] = [];
let listener: Listener | null = null;
let lastKey: string | null = null;

/**
 * Records a route change. `key` is the router location key: a repeated key
 * (StrictMode runs effects twice in dev) is ignored.
 */
export function recordRouteChange(route: string, key: string): void {
  if (key === lastKey) return;
  lastKey = key;
  const change = { route, time: Date.now() };
  if (listener) {
    try {
      listener(change);
    } catch {
      // Tracing must never break navigation.
    }
  } else if (pending.length < MAX_PENDING) {
    pending.push(change);
  }
}

/** Subscribes the SDK; replays buffered changes. Returns the unsubscribe. */
export function onRouteChange(next: Listener): () => void {
  listener = next;
  const replay = pending;
  pending = [];
  for (const change of replay) {
    try {
      next(change);
    } catch {
      // ignore, see recordRouteChange
    }
  }
  return () => {
    if (listener === next) listener = null;
  };
}

/** Test helper: forget buffered changes, the listener and the last key. */
export function resetRouteEvents(): void {
  pending = [];
  listener = null;
  lastKey = null;
}
