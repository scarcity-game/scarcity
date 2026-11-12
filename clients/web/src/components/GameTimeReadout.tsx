import { useEffect, useState } from 'react';
import { currentGameTime, formatRate } from '../game/clock.ts';

const REFRESH_MS = 100;

/** Live game time for an area clock anchor; re-reads the anchor on every refresh. */
export function GameTimeReadout({ clock }: { clock: unknown }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), REFRESH_MS);
    return () => clearInterval(id);
  }, []);
  const time = currentGameTime(clock, now);
  return (
    <p className="game-time" role="timer" aria-label="Game time">
      {time === null ? 'game time —' : `game time ${time.toFixed(2)} (rate ${formatRate(clock)})`}
    </p>
  );
}
