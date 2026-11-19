const reported = new Set<string>();

/** Logs a sprite gap to the console, once per reason for the life of the page. */
export function reportGap(reason: string): void {
  if (reported.has(reason)) return;
  reported.add(reason);
  console.warn(`sprites: ${reason}`);
}

/** For tests: forget what was reported. */
export function resetReportedGaps(): void {
  reported.clear();
}
