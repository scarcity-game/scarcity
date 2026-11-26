import type { CSSProperties } from 'react';
import type { Resource } from '../resources/cache.ts';
import type { CellLookup } from './cellFacts.ts';
import { EntityPanel } from './EntityPanel.tsx';

/** Where the hovered mark is, in pixels from the map's top-left corner, and the map's size. */
export interface Anchor {
  left: number;
  top: number;
  width: number;
  height: number;
  mapWidth: number;
  mapHeight: number;
}

const GAP = 8; // px between the mark and the card

/**
 * The card goes beside the mark, on the side with more room, so it stays inside
 * the map: right of a mark in the left half, left of one in the right half, and
 * likewise below or above.
 */
export function cardPosition(a: Anchor): CSSProperties {
  const centreX = a.left + a.width / 2;
  const centreY = a.top + a.height / 2;
  const horizontal =
    centreX <= a.mapWidth / 2
      ? { left: a.left + a.width + GAP }
      : { right: a.mapWidth - a.left + GAP };
  const vertical =
    centreY <= a.mapHeight / 2 ? { top: a.top } : { bottom: a.mapHeight - a.top - a.height };
  return { ...horizontal, ...vertical };
}

/**
 * The position, plus the edge offset as a custom property so the stylesheet can slide a card that
 * is taller than the room's remaining space back inside the room.
 */
function cardStyle(anchor: Anchor): CSSProperties {
  const position = cardPosition(anchor);
  const edge =
    position.top !== undefined
      ? { '--card-top': `${position.top}px` }
      : { '--card-bottom': `${position.bottom}px` };
  return { ...position, ...edge } as CSSProperties;
}

/** The entity panel as a floating card next to the hovered entity; it takes no pointer events. */
export function HoverCard({
  entity,
  anchor,
  resources,
}: {
  entity: Resource;
  anchor: Anchor;
  resources?: CellLookup;
}) {
  return (
    <div className="hover-card" role="tooltip" style={cardStyle(anchor)}>
      <EntityPanel resource={entity} resources={resources} />
    </div>
  );
}
