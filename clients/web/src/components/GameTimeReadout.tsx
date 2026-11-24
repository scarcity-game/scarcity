import { exactQuantity, formatQuantity } from '../format/units.ts';

/**
 * The area's game time (`area.time`, `docs/actions.md` "Game time"). It
 * shows the value as it is and jumps with every update; it does not step
 * through the times of the log entries being animated. Scaled to a fitting
 * unit; the exact seconds are the tooltip.
 */
export function GameTimeReadout({ time }: { time: unknown }) {
  const valid = typeof time === 'number' && Number.isFinite(time);
  return (
    <p
      className="game-time"
      role="timer"
      aria-label="Game time"
      title={valid ? exactQuantity(time, 'time') : undefined}
    >
      game time {valid ? formatQuantity(time, 'time') : '—'}
    </p>
  );
}
