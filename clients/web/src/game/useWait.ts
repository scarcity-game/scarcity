import { useCallback, useEffect, useState } from 'react';
import { intentErrorMessage, postWait } from '../api/intents.ts';
import type { Request } from '../resources/worldApi.ts';
import type { Resource } from '../resources/cache.ts';

/** The choice of how long to wait, in game seconds; a duration must be above 0. */
export const WAIT_DURATIONS = [1, 5, 15] as const;
export const DEFAULT_WAIT = 5;

/** How long a refusal stays on screen. */
const ERROR_MS = 5000;

export interface Wait {
  busy: boolean;
  error: string | null;
  auto: boolean;
  setAuto: (auto: boolean) => void;
  /** Posts `wait` for the given game seconds. */
  post: (duration: number) => void;
}

interface Outcome {
  busy: boolean;
  error: string | null;
  /** The entity version the last accepted advance left it at; auto waits for the view to hold it. */
  settled: number;
}

/**
 * Posting `wait` for `entity` on the version the view holds, one post at a time. In `auto` mode it
 * posts again whenever it is `myTurn` and the view has caught up with the last answer, and turns
 * itself off at any error.
 */
export function useWait(
  request: Request,
  entity: Resource,
  myTurn: boolean,
  duration: number,
): Wait {
  const [outcome, setOutcome] = useState<Outcome>({ busy: false, error: null, settled: 0 });
  const [auto, setAuto] = useState(false);
  const { id, version } = entity;

  const post = useCallback(
    (seconds: number) => {
      setOutcome((o) => ({ ...o, busy: true, error: null }));
      postWait(request, id, seconds, version).then(
        (result) => setOutcome({ busy: false, error: null, settled: result.version }),
        (err: unknown) => {
          setAuto(false);
          setOutcome((o) => ({ ...o, busy: false, error: intentErrorMessage(err) }));
        },
      );
    },
    [request, id, version],
  );
  const clearError = useCallback(() => setOutcome((o) => ({ ...o, error: null })), []);

  const ready = myTurn && !outcome.busy && version >= outcome.settled;
  useAutoWait(auto && ready, post, duration);
  useClearAfter(outcome.error, clearError);
  return { busy: outcome.busy, error: outcome.error, auto, setAuto, post };
}

function useAutoWait(go: boolean, post: (duration: number) => void, duration: number): void {
  useEffect(() => {
    if (go) post(duration);
  }, [go, post, duration]);
}

/** Clears a shown refusal after a while. */
export function useClearAfter(error: string | null, clear: () => void): void {
  useEffect(() => {
    if (error === null) return;
    const timer = setTimeout(clear, ERROR_MS);
    return () => clearTimeout(timer);
  }, [error, clear]);
}
