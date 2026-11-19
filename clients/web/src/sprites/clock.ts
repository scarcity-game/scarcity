import { createContext, useContext, useEffect, useState } from 'react';

/** How often the clock tells its listeners the time has moved (wall ms). The shortest frame is 120 ms. */
export const CLOCK_TICK_MS = 40;

/** The clock runs at most this fast, however fast the replay plays: faster only aliases the frames. */
export const MAX_RATE = 4;

export interface ClockSettings {
  running: boolean;
  /** Clock ms per wall ms: the animation speed times the replay speed. */
  rate: number;
}

/**
 * The time sprite frames are chosen by. It is not game time and not the beat clock: it runs
 * at `rate` times wall time while `running`, and stands still when not (a paused replay).
 * It ticks only while something listens and it runs. `wall` is injected for tests.
 */
export class SpriteClock {
  private ms = 0;
  private settings: ClockSettings = { running: false, rate: 1 };
  private lastWall: number;
  private readonly listeners = new Set<() => void>();
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(private readonly wall: () => number = () => performance.now()) {
    this.lastWall = wall();
  }

  /** The clock's time in ms; changes only on a tick. */
  now = (): number => this.ms;

  configure(settings: ClockSettings): void {
    this.advance();
    this.settings = { running: settings.running, rate: Math.min(settings.rate, MAX_RATE) };
    this.sync();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    this.sync();
    return () => {
      this.listeners.delete(listener);
      this.sync();
    };
  };

  /** Moves the clock to the wall time and tells the listeners. */
  tick(): void {
    this.advance();
    for (const listener of [...this.listeners]) listener();
  }

  private advance(): void {
    const wall = this.wall();
    if (this.settings.running) this.ms += (wall - this.lastWall) * this.settings.rate;
    this.lastWall = wall;
  }

  private sync(): void {
    const wanted = this.settings.running && this.listeners.size > 0;
    if (wanted && this.timer === null) {
      this.timer = setInterval(() => this.tick(), CLOCK_TICK_MS);
    } else if (!wanted && this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}

/** A clock that never runs: what sprites use outside an area view (and in tests). */
const STOPPED = new SpriteClock();

export const SpriteClockContext = createContext<SpriteClock>(STOPPED);

export function useSpriteClock(): SpriteClock {
  return useContext(SpriteClockContext);
}

/** Makes a clock for a view and keeps it set to `settings`. */
export function useOwnedSpriteClock(settings: ClockSettings): SpriteClock {
  const [clock] = useState(() => new SpriteClock());
  const { running, rate } = settings;
  useEffect(() => clock.configure({ running, rate }), [clock, running, rate]);
  return clock;
}
