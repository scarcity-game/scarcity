/**
 * The area's game time (`area.time`, `docs/actions.md` "Game time"). It
 * shows the value as it is and jumps with every update; it does not step
 * through the times of the log entries being animated.
 */
export function GameTimeReadout({ time }: { time: unknown }) {
  const shown = typeof time === 'number' && Number.isFinite(time) ? time.toFixed(2) : '—';
  return (
    <p className="game-time" role="timer" aria-label="Game time">
      game time {shown}
    </p>
  );
}
