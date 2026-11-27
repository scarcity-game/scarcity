import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { createGame, listProfiles, type Mob, type Profile } from '../api/games.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import { FormError } from '../forms/Field.tsx';
import { gamePath } from '../resources/routeIds.ts';
import { densOf, initialCounts, serverMessage, type Counts } from './newGameForm.ts';

const ARENA = 'arena';

function useProfile(): { profile: Profile | null; error: string | null } {
  const { authRequest } = useAuth();
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    listProfiles(authRequest, controller.signal).then(
      (profiles) => {
        const found = profiles.find((p) => p.name === ARENA);
        if (found) setProfile(found);
        else setError('This server cannot make arena games.');
      },
      (err: unknown) => {
        if (!isAbortError(err)) setError(errorMessage(err));
      },
    );
    return () => controller.abort();
  }, [authRequest]);
  return { profile, error };
}

export function NewGameView() {
  const { profile, error } = useProfile();
  return (
    <>
      <h1>New game</h1>
      {error && <p className="form-error">{error}</p>}
      {!error && profile === null && <p>Loading…</p>}
      {profile && <ArenaForm profile={profile} />}
      <p>
        <Link to="/games">Back to games</Link>
      </p>
    </>
  );
}

function useSubmit(profile: Profile, counts: Counts) {
  const { authRequest } = useAuth();
  const navigate = useNavigate();
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const result = densOf(profile.options.mobs, counts, profile.options.maxDensPerMob);
    if ('error' in result) return setMessage(result.error);
    setMessage(null);
    setPending(true);
    try {
      const made = await createGame(authRequest, profile.name, result.dens);
      void navigate(gamePath(made.game));
    } catch (err) {
      setMessage(serverMessage(err) ?? errorMessage(err));
      setPending(false);
    }
  }
  return { message, pending, submit };
}

function ArenaForm({ profile }: { profile: Profile }) {
  const { mobs, maxDensPerMob } = profile.options;
  const [counts, setCounts] = useState<Counts>(() => initialCounts(mobs));
  const { message, pending, submit } = useSubmit(profile, counts);
  return (
    <form className="auth-form new-game-form" onSubmit={submit} noValidate>
      <p>
        An arena: one room, with a den for each mob below. The mobs fight each other; you watch.
        Each den may hold up to {maxDensPerMob} of its mob, and 0 leaves the mob out.
      </p>
      {mobs.map((mob) => (
        <MobField
          key={mob.prototype}
          mob={mob}
          max={maxDensPerMob}
          value={counts[mob.prototype] ?? ''}
          onChange={(value) => setCounts({ ...counts, [mob.prototype]: value })}
        />
      ))}
      <FormError message={message} />
      <button type="submit" disabled={pending}>
        {pending ? 'Creating…' : 'Create game'}
      </button>
    </form>
  );
}

interface MobFieldProps {
  mob: Mob;
  max: number;
  value: string;
  onChange(value: string): void;
}

function MobField({ mob, max, value, onChange }: MobFieldProps) {
  const id = `dens-${mob.prototype}`;
  return (
    <div className="field">
      <label htmlFor={id}>
        Dens of {mob.name} <span className="field-hint">(faction {mob.faction})</span>
      </label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min={0}
        max={max}
        step={1}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}
