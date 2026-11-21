import { Link, useParams } from 'react-router';
import { gamePath, segmentToId } from '../resources/routeIds.ts';
import { AreaListPanel } from './AreaListPanel.tsx';
import { NotFoundView } from './NotFoundView.tsx';

export function AreaListView() {
  const gameId = segmentToId('game', useParams().gameId);
  return gameId === null ? <NotFoundView /> : <AreaListLoaded gameId={gameId} />;
}

function AreaListLoaded({ gameId }: { gameId: string }) {
  return (
    <>
      <h1>Areas</h1>
      <p>
        <Link to={gamePath(gameId)}>Back to game</Link>
      </p>
      <AreaListPanel gameId={gameId} />
    </>
  );
}
