import type { ReplayPlayer, PlayerState } from './player.ts';
import { SPEEDS } from './player.ts';
import { formatLogTime } from '../log/format.ts';

export function ReplayControls({ player, state, onExit }: { player: ReplayPlayer; state: PlayerState; onExit: () => void }) {
  return (
    <section className="replay-controls" aria-label="Replay controls">
      <p className="replay-banner" role="status">REPLAY, game time {formatLogTime(state.gameTime)}, entry {state.seq}</p>
      <div className="replay-buttons">
        <button type="button" aria-label="Previous action" disabled={!state.hasPrevRoot} onClick={() => player.jumpRoot('prev')}>⏮ Action</button>
        <button type="button" aria-label="Previous entry" disabled={state.index < 0} onClick={() => player.stepEntry(-1)}>◀ Entry</button>
        <button type="button" onClick={() => (state.playing ? player.pause() : player.play())}>{state.playing ? 'Pause' : 'Play'}</button>
        <button type="button" aria-label="Next entry" disabled={state.atEnd} onClick={() => player.stepEntry(1)}>Entry ▶</button>
        <button type="button" aria-label="Next action" disabled={!state.hasNextRoot} onClick={() => player.jumpRoot('next')}>Action ⏭</button>
      </div>
      <div className="replay-speed" role="group" aria-label="Speed">
        {SPEEDS.map(s => (
          <button type="button" key={s} aria-pressed={state.speed === s} onClick={() => player.setSpeed(s)}>{s}×</button>
        ))}
      </div>
      <label className="replay-scrubber">Game time <input type="range" aria-label="Game time" min={state.startTime} max={state.endTime} step="any" value={state.gameTime} onChange={(e) => player.seekTime(Number(e.target.value))} /></label>
      <button type="button" className="replay-exit" onClick={onExit}>Back to live</button>
    </section>
  );
}