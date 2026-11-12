import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import * as authApi from '../api/auth.ts';
import type { Credentials, User } from '../api/auth.ts';
import { apiRequest, isAbortError, isApiError, type RequestOptions } from '../api/client.ts';
import {
  clearStoredSession,
  isExpired,
  loadStoredSession,
  msUntilExpiry,
  storeSession,
  type Session,
} from './sessionStore.ts';

/**
 * - `checking`: a session was restored from sessionStorage and is being
 *   validated with GET /api/auth/me. Neither logged in nor out yet.
 * - `anonymous`: no session. `reason` says why a previous one ended, if any.
 * - `authenticated`: a session whose token the server accepted.
 */
export type AuthState =
  | { status: 'checking'; session: Session }
  | { status: 'anonymous'; reason: SessionEndReason | null }
  | { status: 'authenticated'; session: Session };

/** `logout`: the player chose to; `expired`: past expiresAt; `unauthorized`: a 401. */
export type SessionEndReason = 'logout' | 'expired' | 'unauthorized';

export type AuthStatus = AuthState['status'];

export interface AuthContextValue {
  status: AuthStatus;
  /** The logged-in user (also set while `checking`, from storage). */
  user: User | null;
  token: string | null;
  /** Why the last session ended, while `anonymous` (null if there was none). */
  endReason: SessionEndReason | null;
  /** POST /login, then GET /me with the new token; starts the session. */
  login(credentials: Credentials): Promise<User>;
  /** POST /register only; the caller logs in afterwards. */
  register(credentials: Credentials): Promise<User>;
  /** Forgets the session (client side only; JWTs are not revocable). */
  logout(): void;
  /**
   * Ends the session because the server rejected the token (e.g. the
   * WebSocket closed with 4401), like a 401 from `authRequest` does.
   */
  rejectSession(reason: 'unauthorized' | 'expired'): void;
  /**
   * `apiRequest` with the session's Bearer token. A 401 ends the session
   * (and is rethrown), so protected views fall back to the login page.
   */
  authRequest<T>(path: string, options?: Omit<RequestOptions, 'token'>): Promise<T>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// setTimeout's delay is a signed 32-bit int; longer delays fire immediately.
const MAX_TIMER_DELAY = 2_147_483_647;

function initialState(): AuthState {
  const stored = loadStoredSession();
  if (!stored) return { status: 'anonymous', reason: null };
  if (isExpired(stored)) {
    clearStoredSession();
    return { status: 'anonymous', reason: 'expired' };
  }
  return { status: 'checking', session: stored };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>(initialState);
  // Re-arms the expiry timer when a clamped timeout fires early.
  const [expiryTick, setExpiryTick] = useState(0);

  const session = state.status === 'anonymous' ? null : state.session;
  const sessionRef = useRef<Session | null>(session);
  sessionRef.current = session;

  const startSession = useCallback((next: Session) => {
    sessionRef.current = next;
    storeSession(next);
    setState({ status: 'authenticated', session: next });
  }, []);

  const endSession = useCallback((reason: SessionEndReason) => {
    sessionRef.current = null;
    clearStoredSession();
    setState({ status: 'anonymous', reason });
  }, []);

  const logout = useCallback(() => endSession('logout'), [endSession]);

  // Ends the session only if `token` is still the current one, so a late 401
  // for an old token does not log out a newer session.
  const endSessionIfCurrent = useCallback(
    (token: string, reason: SessionEndReason) => {
      if (sessionRef.current?.token === token) endSession(reason);
    },
    [endSession],
  );

  // Validate a restored session against the server.
  const checkingToken = state.status === 'checking' ? state.session.token : null;
  useEffect(() => {
    if (checkingToken === null) return;
    const controller = new AbortController();
    authApi.me(checkingToken, controller.signal).then(
      (user) => {
        const current = sessionRef.current;
        if (controller.signal.aborted || current?.token !== checkingToken) return;
        startSession({ ...current, user });
      },
      (err: unknown) => {
        if (controller.signal.aborted || isAbortError(err)) return;
        if (isApiError(err) && err.status === 401) {
          endSessionIfCurrent(checkingToken, 'unauthorized');
          return;
        }
        // Server unreachable or erroring: keep the stored session rather than
        // logging the player out because of an outage. The next authenticated
        // call will end it if the token really is bad.
        const current = sessionRef.current;
        if (current?.token === checkingToken) {
          setState({ status: 'authenticated', session: current });
        }
      },
    );
    return () => controller.abort();
  }, [checkingToken, startSession, endSessionIfCurrent]);

  // End the session when the token expires: a timer, plus a check whenever
  // the tab regains focus (timers are throttled or paused in background tabs
  // and while the machine sleeps).
  const activeToken = session?.token ?? null;
  const activeExpiresAt = session?.expiresAt ?? null;
  useEffect(() => {
    if (activeToken === null || activeExpiresAt === null) return;
    const expiry = { expiresAt: activeExpiresAt };
    const check = () => {
      if (isExpired(expiry)) endSessionIfCurrent(activeToken, 'expired');
    };
    const delay = msUntilExpiry(expiry);
    if (delay <= 0) {
      endSessionIfCurrent(activeToken, 'expired');
      return;
    }
    const timer = window.setTimeout(
      () => {
        if (isExpired(expiry)) endSessionIfCurrent(activeToken, 'expired');
        else setExpiryTick((n) => n + 1);
      },
      Math.min(delay, MAX_TIMER_DELAY),
    );
    const onVisibility = () => {
      if (document.visibilityState === 'visible') check();
    };
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('focus', check);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [activeToken, activeExpiresAt, expiryTick, endSessionIfCurrent]);

  const login = useCallback(
    async (credentials: Credentials) => {
      const issued = await authApi.login(credentials);
      const user = await authApi.me(issued.token);
      startSession({ token: issued.token, expiresAt: issued.expiresAt, user });
      return user;
    },
    [startSession],
  );

  const rejectSession = useCallback(
    (reason: 'unauthorized' | 'expired') => endSession(reason),
    [endSession],
  );

  const register = useCallback((credentials: Credentials) => authApi.register(credentials), []);

  const authRequest = useCallback(
    async <T,>(path: string, options: Omit<RequestOptions, 'token'> = {}): Promise<T> => {
      const current = sessionRef.current;
      if (current && isExpired(current)) {
        endSessionIfCurrent(current.token, 'expired');
      }
      const token = current && !isExpired(current) ? current.token : null;
      try {
        return await apiRequest<T>(path, { ...options, token });
      } catch (err) {
        if (token !== null && isApiError(err) && err.status === 401) {
          endSessionIfCurrent(token, 'unauthorized');
        }
        throw err;
      }
    },
    [endSessionIfCurrent],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      status: state.status,
      user: session?.user ?? null,
      token: session?.token ?? null,
      endReason: state.status === 'anonymous' ? state.reason : null,
      login,
      register,
      logout,
      rejectSession,
      authRequest,
    }),
    [state, session, login, register, logout, rejectSession, authRequest],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
