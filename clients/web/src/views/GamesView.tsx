import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { listResources } from '../resources/worldApi.ts';

export const resourceName = (r: Resource): string =>
  typeof r.state.name === 'string' ? r.state.name : r.id;

export function GamesView() {
  const { authRequest } = useAuth();
  const [games, setGames] = useState<Resource[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listResources(authRequest, 'game', controller.signal).then(setGames, (err: unknown) => {
      if (!isAbortError(err)) setError(errorMessage(err));
    });
    return () => controller.abort();
  }, [authRequest]);

  return (
    <>
      <h1>Games</h1>
      {error && <p className="form-error">{error}</p>}
      {!error && games === null && <p>Loading games…</p>}
      {games?.length === 0 && <p>There are no games yet.</p>}
      {games && games.length > 0 && (
        <ul className="resource-list">
          {games.map((game) => (
            <li key={game.id}>
              <Link to={`/games/${encodeURIComponent(game.id)}`}>{resourceName(game)}</Link>{' '}
              <code>{game.id}</code>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
