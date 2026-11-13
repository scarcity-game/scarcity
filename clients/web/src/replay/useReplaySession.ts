import { useCallback, useEffect, useRef, useState } from 'react';
import { loadReplay, type FetchKindPage } from './loader.ts';
import { Replay } from './replay.ts';
import { ReplayPlayer } from './player.ts';
import { errorMessage } from '../api/client.ts';

export type ReplayStatus =
  | { phase: 'idle' }
  | { phase: 'loading'; loaded: number; total: number }
  | { phase: 'error'; message: string }
  | { phase: 'active'; player: ReplayPlayer };

export function useReplaySession(fetchPage: FetchKindPage): {
  status: ReplayStatus;
  start: () => void;
  cancel: () => void;
  exit: () => void
} {
  const [status, setStatus] = useState<ReplayStatus>({phase:'idle'});
  const controllerRef = useRef<AbortController | null>(null);
  const playerRef = useRef<ReplayPlayer | null>(null);
  const fetchRef = useRef(fetchPage);

  // Update the fetchRef on every render
  fetchRef.current = fetchPage;

  const start = useCallback(() => {
    if (controllerRef.current || playerRef.current) {
      return; // A load is running or a replay is active
    }

    const controller = new AbortController();
    controllerRef.current = controller;
    setStatus({phase:'loading', loaded: 0, total: 0});

    loadReplay((after, limit, kind, signal) => fetchRef.current(after, limit, kind, signal), {
      signal: controller.signal,
      onProgress: (loaded, total) => {
        if (!controller.signal.aborted) {
          setStatus({phase:'loading', loaded, total});
        }
      }
    }).then(result => {
      if (controller.signal.aborted) return;

      controllerRef.current = null;

      if (result.kind === 'no-snapshot') {
        setStatus({phase:'error', message:'The log has no snapshot, so this area cannot be replayed.'});
      } else {
        const player = new ReplayPlayer(new Replay(result.snapshot, result.entries));
        playerRef.current = player;
        setStatus({phase:'active', player});
      }
    }).catch(err => {
      if (controller.signal.aborted) return;
      controllerRef.current = null;
      setStatus({phase:'error', message: errorMessage(err)});
    });
  }, []);

  const cancel = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setStatus({phase:'idle'});
  }, []);

  const exit = useCallback(() => {
    playerRef.current?.dispose();
    playerRef.current = null;
    controllerRef.current?.abort();
    controllerRef.current = null;
    setStatus({phase:'idle'});
  }, []);

  useEffect(() => {
    return () => {
      controllerRef.current?.abort();
      playerRef.current?.dispose();
    };
  }, []);

  return {
    status,
    start,
    cancel,
    exit
  };
}