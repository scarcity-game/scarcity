import { afterEach, describe, expect, it, vi } from 'vitest';
import { ALICE, storeTestSession } from '../test/mockApi.ts';
import {
  clearStoredSession,
  loadStoredSession,
  SESSION_STORAGE_KEY,
  storeSession,
} from './sessionStore.ts';

describe('session store', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('round-trips token, expiresAt and user', () => {
    const session = storeTestSession();
    expect(loadStoredSession()).toEqual(session);
    clearStoredSession();
    expect(loadStoredSession()).toBeNull();
  });

  it('drops corrupt or malformed entries', () => {
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, '{nope');
    expect(loadStoredSession()).toBeNull();
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();

    window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ token: 'x' }));
    expect(loadStoredSession()).toBeNull();
  });

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    expect(loadStoredSession()).toBeNull();
    expect(() =>
      storeSession({ token: 't', expiresAt: new Date().toISOString(), user: ALICE }),
    ).not.toThrow();
    expect(() => clearStoredSession()).not.toThrow();
  });
});
