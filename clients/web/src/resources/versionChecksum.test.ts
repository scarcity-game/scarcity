import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { checksumHex, versionChecksum } from './versionChecksum.ts';

// The vectors the server's VersionChecksumTest reads too, so both sides agree.
const vectors = JSON.parse(
  readFileSync('../../libs/resource-model/src/test/resources/golden/version-checksums.json', 'utf8'),
) as { name: string; versions: Record<string, number>; checksum: string }[];

describe('versionChecksum', () => {
  it('has vectors to check', () => {
    expect(vectors.length).toBeGreaterThan(4);
  });

  it.each(vectors)('$name', ({ versions, checksum }) => {
    expect(checksumHex(versionChecksum(Object.entries(versions)))).toBe(checksum);
  });
});
