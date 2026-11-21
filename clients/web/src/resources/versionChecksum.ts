// The version checksum of docs/resources.md, "Versions": the sum, modulo 2^32, of
// low32(id) x version over a set of resources, where low32(id) is the id's last
// 8 hex digits as an unsigned 32-bit number. Math.imul and `>>> 0` keep it in
// 32-bit integers, so it agrees with the server's Java bit for bit, with no BigInt.

/** One resource's term. */
export function checksumTerm(id: string, version: number): number {
  return Math.imul(parseInt(id.slice(-8), 16), version) >>> 0;
}

/** The checksum of `versions` (id to version). */
export function versionChecksum(versions: Iterable<readonly [string, number]>): number {
  let sum = 0;
  for (const [id, version] of versions) sum = (sum + checksumTerm(id, version)) >>> 0;
  return sum;
}

/** As the server sends it: 8 lowercase hex digits. */
export function checksumHex(checksum: number): string {
  return checksum.toString(16).padStart(8, '0');
}
