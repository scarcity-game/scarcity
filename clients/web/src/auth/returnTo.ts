// The return-to target is carried in a `?next=` query parameter, e.g.
// /login?next=%2Fplay. It is user-controllable, so only same-origin relative
// paths are accepted (no open redirect): it must start with exactly one "/",
// and must still resolve to our own origin.

const PROBE_ORIGIN = 'https://scarcity.invalid';

function hasUnsafeChar(raw: string): boolean {
  // Control characters and whitespace (browsers strip tabs/newlines in URLs).
  return [...raw].some((char) => {
    const code = char.charCodeAt(0);
    return code <= 0x1f || code === 0x7f || /[\s\\]/.test(char);
  });
}

// One leading slash; "//host" and "/\host" are protocol-relative in browsers.
function isRelativePath(raw: string): boolean {
  return raw.startsWith('/') && !raw.startsWith('//') && !raw.startsWith('/\\');
}

function parseProbe(raw: string): URL | null {
  try {
    return new URL(raw, PROBE_ORIGIN);
  } catch {
    return null;
  }
}

function isSameOriginPage(raw: string): boolean {
  const url = parseProbe(raw);
  if (url === null || url.origin !== PROBE_ORIGIN) return false;
  // Going "back" to the login or register page after logging in is pointless.
  return url.pathname !== '/login' && url.pathname !== '/register';
}

/** Returns `raw` if it is a safe in-app path, otherwise `fallback`. */
export function safeReturnTo(raw: string | null | undefined, fallback = '/'): string {
  if (typeof raw !== 'string' || raw === '') return fallback;
  if (!isRelativePath(raw) || hasUnsafeChar(raw)) return fallback;
  return isSameOriginPage(raw) ? raw : fallback;
}

/** `/login?next=...` (or /register), carrying `next` only when it is not home. */
export function withNext(path: '/login' | '/register', next: string): string {
  const target = safeReturnTo(next);
  return target === '/' ? path : `${path}?next=${encodeURIComponent(target)}`;
}
