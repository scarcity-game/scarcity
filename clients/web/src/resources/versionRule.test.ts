import { describe, expect, it } from 'vitest';
import { decideChange, decideDigestEntry } from './versionRule.ts';

describe('decideChange', () => {
  it('applies the next version with a patch', () => {
    expect(decideChange(5, 6, true)).toBe('apply');
  });
  it('fetches the next version without a patch (too big)', () => {
    expect(decideChange(5, 6, false)).toBe('fetch');
  });
  it('fetches on a gap', () => {
    expect(decideChange(5, 7, true)).toBe('fetch');
    expect(decideChange(5, 100, false)).toBe('fetch');
  });
  it('ignores equal and lower versions', () => {
    expect(decideChange(5, 5, true)).toBe('ignore');
    expect(decideChange(5, 4, true)).toBe('ignore');
  });
  it('fetches an uncached resource instead of trusting a patch', () => {
    expect(decideChange(undefined, 1, true)).toBe('fetch');
  });
});

describe('decideDigestEntry', () => {
  it('fetches when higher or not cached, ignores otherwise', () => {
    expect(decideDigestEntry(3, 4)).toBe('fetch');
    expect(decideDigestEntry(undefined, 1)).toBe('fetch');
    expect(decideDigestEntry(3, 3)).toBe('ignore');
    expect(decideDigestEntry(3, 2)).toBe('ignore');
  });
});
