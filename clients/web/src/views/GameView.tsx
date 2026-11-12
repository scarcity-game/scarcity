import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { getResource, listChildrenOfType } from '../resources/worldApi.ts';
import { areaPath, segmentToId } from '../resources/routeIds.ts';
import { resourceName } from './GamesView.tsx';
import { NotFoundView } from './NotFoundView.tsx';

export function GameView() {
  const gameId = segmentToId('game', useParams().gameId);
  return gameId === null ? <NotFoundView /> : <GameLoaded gameId={gameId} />;
}

function GameLoaded({ gameId }: { gameId: string }) {
  const { authRequest } = useAuth();
  const [game, setGame] = useState<Resource | null>(null);
  const [areas, setAreas] = useState<Resource[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const fail = (err: unknown) => {
      if (!isAbortError(err)) setError(errorMessage(err));
    };
    getResource(authRequest, gameId, { signal: controller.signal }).then(
      (g) => setGame(g ?? null),
      fail,
    );
    listChildrenOfType(authRequest, gameId, 'area', controller.signal).then(setAreas, fail);
    return () => controller.abort();
  }, [authRequest, gameId]);

  if (areas?.length === 1 && areas[0]) {
    return (
      <Navigate to={areaPath(gameId, areas[0].id)} replace />
    );
  }

  return (
    <>
      <h1>{game ? resourceName(game) : 'Game'}</h1>
      <p>
        <Link to="/games">All games</Link>
      </p>
      {error && <p className="form-error">{error}</p>}
      {!error && areas === null && <p>Loading areas…</p>}
      {areas?.length === 0 && <p>This game has no areas.</p>}
      {areas && areas.length > 1 && (
        <ul className="resource-list">
          {areas.map((area) => (
            <li key={area.id}>
              <Link to={areaPath(gameId, area.id)}>{resourceName(area)}</Link>{' '}
              <code>{area.id}</code>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
