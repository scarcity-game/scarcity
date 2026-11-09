// The session mirrored to sessionStorage, so it survives a reload of the tab
// (but not closing it). The in-memory copy in AuthProvider is the one in use;
// this is only read once, on app load. Every storage access is wrapped in
// try/catch: storage can be disabled, full, or throw in private modes.
import { isUser, type User } from '../api/auth.ts';

export interface Session {
  token: string;
  /** ISO-8601 instant; the token is unusable from this moment on. */
  expiresAt: string;
  user: User;
}

export const SESSION_STORAGE_KEY = 'scarcity.session';

export function loadStoredSession(): Session | null {
  let raw: string | null;
  try {
    raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw === null) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (isSession(value)) return value;
  } catch {
    // fall through: corrupt entry
  }
  clearStoredSession();
  return null;
}

export function storeSession(session: Session): void {
  try {
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Storage unavailable: the session still works in memory until reload.
  }
}

export function clearStoredSession(): void {
  try {
    window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // ignore
  }
}

/** Milliseconds until the session expires (<= 0 when expired or unparseable). */
export function msUntilExpiry(session: Pick<Session, 'expiresAt'>, now: number = Date.now()): number {
  const expiresAt = Date.parse(session.expiresAt);
  return Number.isNaN(expiresAt) ? 0 : expiresAt - now;
}

export function isExpired(session: Pick<Session, 'expiresAt'>, now: number = Date.now()): boolean {
  return msUntilExpiry(session, now) <= 0;
}

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.token === 'string' &&
    v.token !== '' &&
    typeof v.expiresAt === 'string' &&
    isUser(v.user)
  );
}
