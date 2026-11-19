import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { getResource, listChildrenOfType } from '../resources/worldApi.ts';
import { findFollowedArea } from '../resources/followedArea.ts';
import { areaPath, segmentToId } from '../resources/routeIds.ts';
import { resourceName } from './GamesView.tsx';
import { NotFoundView } from './NotFoundView.tsx';

const FOLLOW_RETRY_MS = 2000;

export function GameView() {
  const gameId = segmentToId('game', useParams().gameId);
  return gameId === null ? <NotFoundView /> : <GameLoaded gameId={gameId} />;
}

interface GameAreas {
  game: Resource | null;
  areas: Resource[] | null;
  error: string | null;
  followed: Resource | null;
}

type SetError = (message: string) => void;

function useLoadedGame(gameId: string, setError: SetError) {
  const { authRequest } = useAuth();
  const [game, setGame] = useState<Resource | null>(null);
  const [areas, setAreas] = useState<Resource[] | null>(null);

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
  }, [authRequest, gameId, setError]);

  return { game, areas };
}

// With several areas, go where the game's player (or npc) is. It may be between areas
// (arriving by stairs), so look again until it is found.
type Areas = Resource[] | null;
function useFollowedArea(gameId: string, areas: Areas, setError: SetError): Resource | null {
  const { authRequest } = useAuth();
  const [followed, setFollowed] = useState<Resource | null>(null);

  useEffect(() => {
    if (!areas || areas.length < 2) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const look = () => {
      findFollowedArea(authRequest, gameId, areas, controller.signal).then(
        (area) => {
          if (controller.signal.aborted) return;
          if (area) setFollowed(area);
          else timer = setTimeout(look, FOLLOW_RETRY_MS);
        },
        (err) => {
          if (!isAbortError(err)) setError(errorMessage(err));
        },
      );
    };
    look();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [authRequest, gameId, areas, setError]);

  return followed;
}

function useGameAreas(gameId: string): GameAreas {
  const [error, setError] = useState<string | null>(null);
  const { game, areas } = useLoadedGame(gameId, setError);
  const followed = useFollowedArea(gameId, areas, setError);
  return { game, areas, error, followed };
}

function AreaList({ gameId, areas }: { gameId: string; areas: Resource[] }) {
  return (
    <>
      <p>Looking for the area where the player or npc is…</p>
      <ul className="resource-list">
        {areas.map((area) => (
          <li key={area.id}>
            <Link to={areaPath(gameId, area.id)}>{resourceName(area)}</Link> <code>{area.id}</code>
          </li>
        ))}
      </ul>
    </>
  );
}

function AreaStatus({
  gameId,
  areas,
  error,
}: {
  gameId: string;
  areas: Resource[] | null;
  error: string | null;
}) {
  return (
    <>
      {error && <p className="form-error">{error}</p>}
      {!error && areas === null && <p>Loading areas…</p>}
      {areas?.length === 0 && <p>This game has no areas.</p>}
      {areas && areas.length > 1 && <AreaList gameId={gameId} areas={areas} />}
    </>
  );
}

function GameLoaded({ gameId }: { gameId: string }) {
  const { game, areas, error, followed } = useGameAreas(gameId);

  const target = areas?.length === 1 ? areas[0] : followed;
  if (target) return <Navigate to={areaPath(gameId, target.id)} replace />;

  return (
    <>
      <h1>{game ? resourceName(game) : 'Game'}</h1>
      <p>
        <Link to="/games">All games</Link>
      </p>
      <AreaStatus gameId={gameId} areas={areas} error={error} />
    </>
  );
}
