import { useCallback, useEffect, useState } from 'react';
import { loadReplay, type FetchKindPage, type LoadResult } from './loader.ts';
import { Replay } from './replay.ts';
import { ReplayPlayer } from './player.ts';
import { errorMessage } from '../api/client.ts';

export type ReplayStatus =
  | { phase: 'idle' }
  | { phase: 'loading'; loaded: number; total: number }
  | { phase: 'error'; message: string }
  | { phase: 'active'; player: ReplayPlayer };

export interface ReplaySession {
  status: ReplayStatus;
  start: () => void;
  cancel: () => void;
  exit: () => void;
}

/** What the session holds outside React state; one object for the life of the hook. */
interface SessionRefs {
  controller: AbortController | null;
  player: ReplayPlayer | null;
  fetchPage: FetchKindPage;
}

type SetStatus = (status: ReplayStatus) => void;

function statusOf(result: LoadResult, refs: SessionRefs): ReplayStatus {
  if (result.kind === 'no-snapshot') {
    return { phase: 'error', message: 'The log has no snapshot, so this area cannot be replayed.' };
  }
  const player = new ReplayPlayer(new Replay(result.snapshot, result.entries));
  refs.player = player;
  return { phase: 'active', player };
}

function startLoad(refs: SessionRefs, setStatus: SetStatus): void {
  if (refs.controller || refs.player) {
    return; // A load is running or a replay is active
  }
  const controller = new AbortController();
  refs.controller = controller;
  setStatus({ phase: 'loading', loaded: 0, total: 0 });

  loadReplay((after, limit, kind, signal) => refs.fetchPage(after, limit, kind, signal), {
    signal: controller.signal,
    onProgress: (loaded, total) => {
      if (!controller.signal.aborted) setStatus({ phase: 'loading', loaded, total });
    },
  })
    .then((result) => {
      if (controller.signal.aborted) return;
      refs.controller = null;
      setStatus(statusOf(result, refs));
    })
    .catch((err) => {
      if (controller.signal.aborted) return;
      refs.controller = null;
      setStatus({ phase: 'error', message: errorMessage(err) });
    });
}

export function useReplaySession(fetchPage: FetchKindPage): ReplaySession {
  const [status, setStatus] = useState<ReplayStatus>({ phase: 'idle' });
  const [refs] = useState<SessionRefs>(() => ({ controller: null, player: null, fetchPage }));
  // Update the fetch function on every render
  refs.fetchPage = fetchPage;

  const start = useCallback(() => startLoad(refs, setStatus), [refs]);

  const cancel = useCallback(() => {
    refs.controller?.abort();
    refs.controller = null;
    setStatus({ phase: 'idle' });
  }, [refs]);

  const exit = useCallback(() => {
    refs.player?.dispose();
    refs.player = null;
    cancel();
  }, [refs, cancel]);

  useEffect(() => {
    return () => {
      refs.controller?.abort();
      refs.player?.dispose();
    };
  }, [refs]);

  return { status, start, cancel, exit };
}
