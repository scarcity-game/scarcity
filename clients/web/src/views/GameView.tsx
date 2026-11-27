import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { ListedResource, Resource } from '../resources/cache.ts';
import { getResource } from '../resources/worldApi.ts';
import { findFollowedArea } from '../resources/followedArea.ts';
import { areaPath, segmentToId } from '../resources/routeIds.ts';
import { AreaListPanel } from './AreaListPanel.tsx';
import { resourceName } from './GamesView.tsx';
import { NotFoundView } from './NotFoundView.tsx';

const FOLLOW_RETRY_MS = 2000;

export function GameView() {
  const gameId = segmentToId('game', useParams().gameId);
  return gameId === null ? <NotFoundView /> : <GameLoaded gameId={gameId} />;
}

interface GameAreas {
  game: Resource | null;
  error: string | null;
  followed: ListedResource | null;
}

type SetError = (message: string) => void;

function useLoadedGame(gameId: string, setError: SetError) {
  const { authRequest } = useAuth();
  const [game, setGame] = useState<Resource | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const fail = (err: unknown) => {
      if (!isAbortError(err)) setError(errorMessage(err));
    };
    getResource(authRequest, gameId, { signal: controller.signal }).then(
      (g) => setGame(g ?? null),
      fail,
    );
    return () => controller.abort();
  }, [authRequest, gameId, setError]);

  return game;
}

// With several areas, go where the game's player (or npc) is. It may be between areas
// (arriving by stairs), so look again until it is found.
type Followed = ListedResource | null;
function useFollowedArea(gameId: string, areaCount: number | null, setError: SetError): Followed {
  const { authRequest } = useAuth();
  const [followed, setFollowed] = useState<Followed>(null);

  useEffect(() => {
    if (areaCount === null || areaCount < 2) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const look = () => {
      findFollowedArea(authRequest, gameId, controller.signal).then(
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
  }, [authRequest, gameId, areaCount, setError]);

  return followed;
}

function useGameAreas(gameId: string): GameAreas {
  const [error, setError] = useState<string | null>(null);
  const game = useLoadedGame(gameId, setError);
  const followed = useFollowedArea(gameId, game ? game.children.length : null, setError);
  return { game, error, followed };
}

// With several areas the list shows while the followed one is looked for; with one there is
// nothing to look for, and going straight to it would make "Back to game" bounce back.
function AreaList({ gameId, count }: { gameId: string; count: number }) {
  return (
    <>
      {count > 1 && <p>Looking for the area where the player or npc is…</p>}
      <AreaListPanel gameId={gameId} defaultFilter={count > 1 ? 'active' : 'all'} />
    </>
  );
}

// The game's own `children` are its areas (a GET by id lists them).
function AreaStatus({
  gameId,
  game,
  error,
}: {
  gameId: string;
  game: Resource | null;
  error: string | null;
}) {
  const count = game ? game.children.length : null;
  return (
    <>
      {error && <p className="form-error">{error}</p>}
      {!error && count === null && <p>Loading areas…</p>}
      {count === 0 && <p>This game has no areas.</p>}
      {count !== null && count > 0 && <AreaList gameId={gameId} count={count} />}
    </>
  );
}

function GameLoaded({ gameId }: { gameId: string }) {
  const { game, error, followed } = useGameAreas(gameId);

  if (followed) return <Navigate to={areaPath(gameId, followed.id)} replace />;

  return (
    <>
      <h1>{game ? resourceName(game) : 'Game'}</h1>
      <p>
        <Link to="/games">All games</Link>
      </p>
      <AreaStatus gameId={gameId} game={game} error={error} />
    </>
  );
}
