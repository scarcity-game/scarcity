import { useSyncExternalStore } from 'react';

/**
 * Whether the room skips animations when it falls behind: with more than one
 * advance waiting, the older ones snap to their end state. Per browser, off by
 * default (every advance plays). Read from storage every time, so all views and
 * tabs agree.
 */
export const STORAGE_KEY = 'scarcity.skipBacklog.v1';

const listeners = new Set<() => void>();

export function skipBacklog(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

export function setSkipBacklog(skip: boolean): void {
  try {
    if (skip) window.localStorage.setItem(STORAGE_KEY, 'true');
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage is blocked or full: the choice just isn't remembered.
  }
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY || event.key === null) listener();
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

export function useSkipBacklog(): [boolean, (skip: boolean) => void] {
  return [useSyncExternalStore(subscribe, skipBacklog), setSkipBacklog];
}
