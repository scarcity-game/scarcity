// The return-to target is carried in a `?next=` query parameter, e.g.
// /login?next=%2Fplay. It is user-controllable, so only same-origin relative
// paths are accepted (no open redirect): it must start with exactly one "/",
// and must still resolve to our own origin.

const PROBE_ORIGIN = 'https://scarcity.invalid';

/** Returns `raw` if it is a safe in-app path, otherwise `fallback`. */
export function safeReturnTo(raw: string | null | undefined, fallback = '/'): string {
  if (typeof raw !== 'string' || raw === '') return fallback;
  // One leading slash; "//host" and "/\host" are protocol-relative in browsers.
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return fallback;
  // Control characters and whitespace (browsers strip tabs/newlines in URLs).
  if (/[\x00-\x1f\x7f\s\\]/.test(raw)) return fallback;
  let url: URL;
  try {
    url = new URL(raw, PROBE_ORIGIN);
  } catch {
    return fallback;
  }
  if (url.origin !== PROBE_ORIGIN) return fallback;
  // Going "back" to the login or register page after logging in is pointless.
  if (url.pathname === '/login' || url.pathname === '/register') return fallback;
  return raw;
}

/** `/login?next=...` (or /register), carrying `next` only when it is not home. */
export function withNext(path: '/login' | '/register', next: string): string {
  const target = safeReturnTo(next);
  return target === '/' ? path : `${path}?next=${encodeURIComponent(target)}`;
}
