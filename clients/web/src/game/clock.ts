/**
 * Current game time from an area's clock anchor:
 * `gameTime + (nowMillis - wallTime) / 1000 * rate`.
 * Returns null when the clock is missing or malformed.
 */
export function currentGameTime(clock: unknown, nowMillis: number): number | null {
  if (typeof clock !== 'object' || clock === null) return null;
  const { gameTime, wallTime, rate } = clock as Record<string, unknown>;
  if (
    typeof gameTime !== 'number' ||
    typeof wallTime !== 'number' ||
    typeof rate !== 'number' ||
    !Number.isFinite(gameTime) ||
    !Number.isFinite(wallTime) ||
    !Number.isFinite(rate)
  ) {
    return null;
  }
  return gameTime + ((nowMillis - wallTime) / 1000) * rate;
}

/** The clock's rate as text, always with a decimal point (`1.0`, `2.5`). */
export function formatRate(clock: unknown): string {
  const rate = (clock as { rate?: unknown } | null)?.rate;
  if (typeof rate !== 'number') return '';
  return Number.isInteger(rate) ? rate.toFixed(1) : String(rate);
}
