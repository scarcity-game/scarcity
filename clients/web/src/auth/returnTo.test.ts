import { describe, expect, it } from 'vitest';
import { safeReturnTo, withNext } from './returnTo.ts';

describe('safeReturnTo', () => {
  it.each(['/play', '/play?seed=4', '/play#log', '/a/b/c', '/%2F%2Fevil.com'])(
    'accepts the same-origin path %s',
    (path) => {
      expect(safeReturnTo(path)).toBe(path);
    },
  );

  it.each([
    null,
    undefined,
    '',
    'play',
    '//evil.example',
    '/\\evil.example',
    '\\\\evil.example',
    'https://evil.example/play',
    'javascript:alert(1)',
    '/\t/evil.example',
    ' /play',
    '/login',
    '/register?next=/play',
  ])('rejects %j', (path) => {
    expect(safeReturnTo(path)).toBe('/');
  });

  it('builds login and register links that carry next', () => {
    expect(withNext('/login', '/play?x=1')).toBe('/login?next=%2Fplay%3Fx%3D1');
    expect(withNext('/register', '/')).toBe('/register');
    expect(withNext('/login', '//evil.example')).toBe('/login');
  });
});
