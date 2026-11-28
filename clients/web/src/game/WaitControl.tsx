import { useState } from 'react';
import { useAuth } from '../auth/AuthProvider.tsx';
import { formatQuantity } from '../format/units.ts';
import type { Resource } from '../resources/cache.ts';
import { resourceName } from '../views/GamesView.tsx';
import { DEFAULT_WAIT, WAIT_DURATIONS, useWait } from './useWait.ts';

/** The action bar's `wait`: how long, the button, and Auto (wait again each turn). */
export function WaitAction({ entity, myTurn }: { entity: Resource; myTurn: boolean }) {
  const { authRequest } = useAuth();
  const [duration, setDuration] = useState<number>(DEFAULT_WAIT);
  const wait = useWait(authRequest, entity, myTurn, duration);
  return (
    <div className="wait-control" role="group" aria-label="Wait">
      <DurationSelect duration={duration} onChange={setDuration} />
      <button type="button" disabled={!myTurn || wait.busy} onClick={() => wait.post(duration)}>
        Wait
      </button>
      <label>
        <input
          type="checkbox"
          checked={wait.auto}
          onChange={(e) => wait.setAuto(e.target.checked)}
        />{' '}
        Auto
      </label>
      {wait.error && (
        <span className="form-error" role="alert">
          {resourceName(entity)}: {wait.error}
        </span>
      )}
    </div>
  );
}

function DurationSelect({
  duration,
  onChange,
}: {
  duration: number;
  onChange: (duration: number) => void;
}) {
  return (
    <label>
      Wait for{' '}
      <select value={duration} onChange={(e) => onChange(Number(e.target.value))}>
        {WAIT_DURATIONS.map((d) => (
          <option key={d} value={d}>
            {formatQuantity(d, 'time')}
          </option>
        ))}
      </select>
    </label>
  );
}
