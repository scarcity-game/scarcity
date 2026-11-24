import { describe, expect, it } from 'vitest';
import { buildNames } from './buildNames';

describe('buildNames', () => {
  it('puts the sha and the content hash in every emitted file name under assets/', () => {
    const names = buildNames('abc1234');
    for (const pattern of Object.values(names)) {
      expect(pattern).toMatch(/^assets\/\[name\]-abc1234-\[hash\]/);
    }
    expect(names.assetFileNames).toContain('[extname]');
  });
});
