import type { Den, Mob } from '../api/games.ts';
import { ApiError } from '../api/client.ts';

/** The den counts by mob prototype, as the form's inputs hold them (text, so a field can be empty). */
export type Counts = Record<string, string>;

export const initialCounts = (mobs: Mob[]): Counts =>
  Object.fromEntries(mobs.map((mob, i) => [mob.prototype, i === 0 ? '1' : '0']));

/** A whole number of 0 to `max`, else null. */
export function parseCount(text: string, max: number): number | null {
  if (!/^\d+$/.test(text.trim())) return null;
  const n = Number(text);
  return n <= max ? n : null;
}

export type DensResult = { dens: Den[] } | { error: string };

/** The dens to send (a mob with count 0 is left out), or what is wrong with the counts. */
export function densOf(mobs: Mob[], counts: Counts, max: number): DensResult {
  const dens: Den[] = [];
  for (const mob of mobs) {
    const count = parseCount(counts[mob.prototype] ?? '', max);
    if (count === null)
      return { error: `The number of ${mob.name} dens must be a whole number from 0 to ${max}.` };
    if (count > 0) dens.push({ prototype: mob.prototype, count });
  }
  return dens.length === 0 ? { error: 'Choose at least one den.' } : { dens };
}

/** The server's `{"error"}` message of a 400, else undefined. */
export function serverMessage(err: unknown): string | undefined {
  const message = err instanceof ApiError && err.status === 400 ? err.problem?.error : undefined;
  return typeof message === 'string' && message !== '' ? message : undefined;
}
