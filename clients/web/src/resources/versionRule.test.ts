import { describe, expect, it } from 'vitest';
import { decideChange } from './versionRule.ts';

describe('decideChange', () => {
  it('applies the next version when it is an update', () => {
    expect(decideChange(5, 6, true)).toBe('apply');
  });
  it('fetches the next version when it is a stub (too big)', () => {
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
  it('fetches an uncached resource: a delta needs a base', () => {
    expect(decideChange(undefined, 1, true)).toBe('fetch');
  });
});
