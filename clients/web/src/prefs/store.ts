import { useSyncExternalStore } from 'react';

/**
 * One per-browser preference, kept in `localStorage` as JSON under its own key.
 * Only a value other than the fallback is stored, so a default that changes
 * reaches everyone who never touched it.
 */
export interface Preference<T> {
  /** Never given a new meaning: a change of meaning gets a new key (`.v2`). */
  storageKey: string;
  fallback: T;
  /** The stored JSON as a value of this preference, or `undefined` when it isn't one. */
  parse: (stored: unknown) => T | undefined;
}

/** A yes/no preference; anything stored that isn't a boolean reads as the fallback. */
export function booleanPreference(storageKey: string, fallback = false): Preference<boolean> {
  return {
    storageKey,
    fallback,
    parse: (stored) => (typeof stored === 'boolean' ? stored : undefined),
  };
}

const listeners = new Set<() => void>();
// The last value read per key, with the stored text it came from: a snapshot stays the same
// object until the stored text changes (`useSyncExternalStore` needs that for object values).
const snapshots = new Map<string, { raw: string | null; value: unknown }>();

function storedText(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function parsed<T>(preference: Preference<T>, raw: string | null): T {
  if (raw === null) return preference.fallback;
  try {
    return preference.parse(JSON.parse(raw)) ?? preference.fallback;
  } catch {
    return preference.fallback;
  }
}

/** The preference's value in this browser, read from storage each time so all views and tabs agree. */
export function readPreference<T>(preference: Preference<T>): T {
  const raw = storedText(preference.storageKey);
  const last = snapshots.get(preference.storageKey);
  if (last && last.raw === raw) return last.value as T;
  const value = parsed(preference, raw);
  snapshots.set(preference.storageKey, { raw, value });
  return value;
}

/** Sets the preference; the fallback is stored as nothing. Every view showing it updates. */
export function writePreference<T>(preference: Preference<T>, value: T): void {
  try {
    if (Object.is(value, preference.fallback))
      window.localStorage.removeItem(preference.storageKey);
    else window.localStorage.setItem(preference.storageKey, JSON.stringify(value));
  } catch {
    // Storage is blocked or full: the choice just isn't remembered.
  }
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  // Another tab changed storage; every preference re-reads, and the unchanged ones keep their snapshot.
  window.addEventListener('storage', listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', listener);
  };
}

/** The preference's value and its setter, re-rendering when it changes here or in another tab. */
export function usePreference<T>(preference: Preference<T>): [T, (value: T) => void] {
  const value = useSyncExternalStore(subscribe, () => readPreference(preference));
  return [value, (next: T) => writePreference(preference, next)];
}
