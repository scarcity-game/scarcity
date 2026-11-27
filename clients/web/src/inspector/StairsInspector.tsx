import { Link } from 'react-router';
import type { Resource } from '../resources/cache.ts';
import { areaPath } from '../resources/routeIds.ts';
import { EntityPanel } from './EntityPanel.tsx';
import type { CellLookup } from './cellFacts.ts';
import { nameOf } from './facts.ts';
import { useStairsDestination, type Destination } from './useStairsDestination.ts';

/**
 * The way to the level on the other side: a real link (it opens in a new tab too), styled as a
 * button. It only moves the viewer's view; nothing happens in the game.
 */
function GoToLevel({ gameId, destination }: { gameId: string; destination: Destination }) {
  switch (destination.status) {
    case 'found':
      return (
        <Link className="inspector-go" to={areaPath(gameId, destination.area.id)}>
          Go to {nameOf(destination.area)}
        </Link>
      );
    case 'loading':
      return (
        <span className="inspector-go" aria-disabled="true" role="status">
          Finding the level these stairs lead to…
        </span>
      );
    case 'error':
      return (
        <span className="inspector-go" aria-disabled="true" role="alert">
          {destination.message}
        </span>
      );
    case 'none':
      return (
        <span className="inspector-go" aria-disabled="true">
          No level there yet: these stairs lead nowhere until one is made.
        </span>
      );
  }
}

interface StairsInspectorProps {
  stairs: Resource;
  gameId: string;
  stale: boolean;
  resources: CellLookup;
}

/** The entity panel of stairs, naming the level they lead to, and the link to it. */
export function StairsInspector({ stairs, gameId, stale, resources }: StairsInspectorProps) {
  const destination = useStairsDestination(stairs);
  const leadsTo = destination.status === 'found' ? nameOf(destination.area) : undefined;
  return (
    <>
      <EntityPanel resource={stairs} stale={stale} resources={resources} leadsTo={leadsTo} />
      <GoToLevel gameId={gameId} destination={destination} />
    </>
  );
}
