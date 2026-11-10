import { describe, expect, it } from 'vitest';
import { scrubAttributes, scrubPageUrl, scrubResourceUrl, scrubUrl } from './scrub.ts';

describe('scrubUrl', () => {
  it.each([
    ['/api/auth/me?token=abc#frag', `${window.location.origin}/api/auth/me`],
    ['https://user:pass@example.com/a/b?q=1', 'https://example.com/a/b'],
    ['http://localhost:3000/', 'http://localhost:3000/'],
    ['http://[::1', '[invalid url]'],
  ])('%s -> %s', (input, expected) => {
    expect(scrubUrl(input)).toBe(expected);
  });
});

describe('scrubAttributes', () => {
  it('keeps allowlisted attributes, scrubs URLs and drops the rest', () => {
    expect(
      scrubAttributes({
        'http.request.method': 'POST',
        'http.response.status_code': 401,
        'url.full': 'http://localhost:3000/api/auth/login?next=%2Fplay',
        'http.url': 'http://localhost:3000/api/auth/login?next=%2Fplay',
        'user_agent.original': 'Mozilla/5.0',
        'http.request.header.authorization': ['Bearer abc'],
        'enduser.id': 'alice',
      }),
    ).toEqual({
      'http.request.method': 'POST',
      'http.response.status_code': 401,
      'url.full': 'http://localhost:3000/api/auth/login',
    });
  });
});

describe('scrubPageUrl', () => {
  it('replaces the path with the route pattern', () => {
    const route = (pathname: string) => (pathname === '/play' ? '/play' : '/*');
    expect(scrubPageUrl('/play?next=x#y', route)).toBe(`${window.location.origin}/play`);
    expect(scrubPageUrl('/profile/alice', route)).toBe(`${window.location.origin}/*`);
  });
});

describe('scrubResourceUrl', () => {
  const origin = window.location.origin;
  it.each([
    [`${origin}/assets/index-AbC123.js?v=1`, `${origin}/assets/index-AbC123.js`],
    [`${origin}/api/auth/me`, `${origin}/api/auth/*`],
    [`${origin}/api/users/6f1c2a9e-1111/inventory?x=1`, `${origin}/api/users/*`],
    [`${origin}/api/auth`, `${origin}/api/auth/*`],
    [`${origin}/api`, origin],
    [`${origin}/apiary/x`, origin],
    [`${origin}/profile/alice`, origin],
    ['https://fonts.example.com/css/family=x', 'https://fonts.example.com'],
  ])('%s -> %s', (input, expected) => {
    expect(scrubResourceUrl(input)).toBe(expected);
  });
});
