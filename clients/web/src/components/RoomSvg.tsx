import { useId, type KeyboardEvent, type MouseEvent } from 'react';
import { nameOf } from '../inspector/facts.ts';
import type { Resource } from '../resources/cache.ts';
import { drawnRadius } from '../sprites/choice.ts';
import { EntitySprite } from '../sprites/Sprite.tsx';
import { fillOf } from './materials.ts';
import {
  boundsOfShapes,
  polygonOf,
  positionOf as pointOf,
  svgBoxOf,
  toSvg,
  type Point,
} from './shapes.ts';
import { useMapView } from './useMapView.ts';

/**
 * Where an entity is moving to, while it moves: a `step` (or any `channel`
 * action) with a `params.to`. Null when it isn't moving.
 */
export function moveTarget(state: Resource): Point | null {
  const action = state.actionInProgress as
    { type?: unknown; timing?: unknown; params?: { to?: Partial<Point> } } | undefined;
  if (!action || (action.type !== 'step' && action.timing !== 'channel')) return null;
  const to = action.params?.to;
  return to && typeof to.x === 'number' && typeof to.y === 'number' ? { x: to.x, y: to.y } : null;
}

const PAD = 0.5; // world units around the room inside the viewBox

function Ring({
  x,
  y,
  r,
  className,
  strokeWidth,
  dashed,
}: {
  x: number;
  y: number;
  r: number;
  className: string;
  strokeWidth: number;
  dashed?: boolean;
}) {
  return (
    <circle
      className={className}
      cx={x}
      cy={y}
      r={r}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeDasharray={dashed ? '4 3' : undefined}
      vectorEffect="non-scaling-stroke"
    />
  );
}

interface RingFlags {
  highlighted: boolean;
  acting: boolean;
  selected: boolean;
}

function Rings({ x, y, r, flags }: { x: number; y: number; r: number; flags: RingFlags }) {
  const { highlighted, acting, selected } = flags;
  return (
    <>
      {selected && <Ring x={x} y={y} r={r + 0.35} className="selected-ring" strokeWidth={2} />}
      {acting && <Ring x={x} y={y} r={r + 0.15} className="acting-ring" strokeWidth={2} />}
      {highlighted && (
        <Ring x={x} y={y} r={r + 0.25} className="highlight-ring" strokeWidth={3} dashed />
      )}
    </>
  );
}

/** What the room tells its owner: a mark is selected (null: the empty map), or hovered. */
export interface RoomEvents {
  onSelect?: (entityId: string | null) => void;
  /** The hovered or focused mark's id and element; null when it is left. */
  onHover?: (entityId: string | null, mark: SVGGElement | null) => void;
}

function isActivation(e: KeyboardEvent): boolean {
  return e.key === 'Enter' || e.key === ' ';
}

function markHandlers(id: string, { onSelect, onHover }: RoomEvents) {
  const select = () => onSelect?.(id);
  const enter = (e: { currentTarget: SVGGElement }) => onHover?.(id, e.currentTarget);
  const leave = () => onHover?.(null, null);
  return {
    onClick: (e: MouseEvent) => {
      e.stopPropagation();
      select();
    },
    onKeyDown: (e: KeyboardEvent) => {
      if (!isActivation(e)) return;
      e.preventDefault();
      select();
    },
    onPointerEnter: enter,
    onFocus: enter,
    onPointerLeave: leave,
    onBlur: leave,
  };
}

interface EntityMarkProps {
  entity: Resource;
  pos: Point;
  flags: RingFlags;
  events: RoomEvents;
  actedUpon?: ReadonlySet<string>;
}

/** A mark: no label (#79); its name is its accessible name, and the entity panel shows the rest. */
function EntityMark({ entity, pos, flags, events, actedUpon }: EntityMarkProps) {
  const { x, y } = toSvg(pos);
  return (
    <g
      data-entity-id={entity.id}
      data-kind={String(entity.entityKind)}
      className={flags.highlighted ? 'entity-mark highlighted' : 'entity-mark'}
      data-acting={flags.acting ? 'true' : undefined}
      data-selected={flags.selected ? 'true' : undefined}
      role="button"
      tabIndex={0}
      aria-label={nameOf(entity)}
      aria-pressed={flags.selected}
      {...markHandlers(entity.id, events)}
    >
      <EntitySprite entity={entity} actedUpon={actedUpon} x={x} y={y} />
      <Rings x={x} y={y} r={drawnRadius(entity.radius)} flags={flags} />
    </g>
  );
}

interface RoomSvgProps extends RoomEvents {
  entities: Resource[];
  /** The entity a log entry under the pointer is about; drawn with a ring. */
  highlightId?: string | null;
  /** The entity the view stays centred on (a spectator follows it); a manual pan calls `onStopFollow`. */
  followedId?: string | null;
  onStopFollow?: () => void;
  /** The selected entity; drawn with a ring. */
  selectedId?: string | null;
  /** Entities whose action is playing in the beat on screen; each gets a ring. */
  acting?: ReadonlySet<string>;
  /** Entities an effect of the beat on screen has as its target; their sprite shows being acted upon. */
  actedUpon?: ReadonlySet<string>;
}

function flagsOf(id: string, { highlightId, selectedId, acting }: RoomSvgProps): RingFlags {
  return {
    highlighted: id === highlightId,
    selected: id === selectedId,
    acting: acting?.has(id) ?? false,
  };
}

/** The view's follow: the followed entity's drawn position, when it is in this room. */
function followOf({ followedId, onStopFollow, entities }: RoomSvgProps) {
  const entity = followedId ? entities.find((e) => e.id === followedId) : undefined;
  const pos = entity && pointOf(entity);
  if (!pos) return null;
  return { point: toSvg(pos), stop: onStopFollow ?? (() => {}) };
}

/**
 * The area as an SVG in world units. The world's origin is bottom-left with y
 * up, SVG's is top-left with y down, so every y is drawn at `height - y`.
 * A click on a mark selects it; a click on the empty map clears the selection.
 */
export function RoomSvg(props: RoomSvgProps) {
  const { onSelect } = props;
  const follow = followOf(props);
  const bounds = boundsOfShapes(props.entities) ?? { minX: 0, minY: 0, maxX: 1, maxY: 1 };
  const view = useMapView(svgBoxOf(bounds, PAD), follow);
  return (
    <>
      <svg
        className="room-svg"
        role="img"
        aria-label="Room"
        aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown + - 0"
        tabIndex={0}
        viewBox={view.viewBox}
        preserveAspectRatio="xMidYMid meet"
        onClick={() => onSelect?.(null)}
        {...view.svgProps}
      >
        <PolygonMarks {...props} />
        <MoveArrows entities={props.entities} />
        <RoomMarks {...props} />
      </svg>
      <MapControls view={view} />
    </>
  );
}

/** Entities with a polygon, drawn first so the circle entities are above them. Keyed on the shape, never on a kind. */
function PolygonMarks(props: RoomSvgProps) {
  const { entities, onSelect, onHover } = props;
  const events: RoomEvents = { onSelect, onHover };
  return entities.map((entity) => {
    const polygon = polygonOf(entity);
    if (!polygon) return null;
    const flags = flagsOf(entity.id, props);
    return (
      <PolygonMark
        key={entity.id}
        entity={entity}
        polygon={polygon}
        flags={flags}
        events={events}
      />
    );
  });
}

interface PolygonMarkProps {
  entity: Resource;
  polygon: Point[];
  flags: RingFlags;
  events: RoomEvents;
}

function PolygonOutline({ points, flags }: { points: string; flags: RingFlags }) {
  if (!flags.selected && !flags.highlighted) return null;
  return (
    <polygon
      className={flags.selected ? 'selected-ring' : 'highlight-ring'}
      points={points}
      fill="none"
      stroke="currentColor"
      strokeWidth={3}
      strokeDasharray={flags.selected ? undefined : '4 3'}
      vectorEffect="non-scaling-stroke"
    />
  );
}

function PolygonMark({ entity, polygon, flags, events }: PolygonMarkProps) {
  const points = polygon
    .map(toSvg)
    .map(({ x, y }) => `${x},${y}`)
    .join(' ');
  return (
    <g
      data-entity-id={entity.id}
      data-kind={String(entity.entityKind)}
      data-shape="polygon"
      className={flags.highlighted ? 'entity-mark highlighted' : 'entity-mark'}
      data-selected={flags.selected ? 'true' : undefined}
      role="button"
      tabIndex={0}
      aria-label={nameOf(entity)}
      aria-pressed={flags.selected}
      {...markHandlers(entity.id, events)}
    >
      <polygon
        className="polygon-shape"
        points={points}
        fill={fillOf(entity.material)}
        stroke="#2b2d31"
        strokeWidth={1}
        vectorEffect="non-scaling-stroke"
      />
      <PolygonOutline points={points} flags={flags} />
    </g>
  );
}

function RoomMarks(props: RoomSvgProps) {
  const { entities, actedUpon, onSelect, onHover } = props;
  const events: RoomEvents = { onSelect, onHover };
  return entities.map((entity) => {
    const pos = pointOf(entity);
    if (!pos || polygonOf(entity)) return null;
    return (
      <EntityMark
        key={entity.id}
        entity={entity}
        pos={pos}
        flags={flagsOf(entity.id, props)}
        actedUpon={actedUpon}
        events={events}
      />
    );
  });
}

/**
 * An arrow from each moving entity to where it is going, under the marks, so
 * a move in progress shows while other things happen. It shrinks as the
 * entity gets there and is gone when the move ends.
 */
function MoveArrows({ entities }: { entities: Resource[] }) {
  const markerId = useId();
  const arrows = entities.flatMap((entity) => {
    const from = pointOf(entity);
    const to = moveTarget(entity);
    if (!from || !to || (from.x === to.x && from.y === to.y)) return [];
    return [{ id: entity.id, a: toSvg(from), b: toSvg(to) }];
  });
  if (arrows.length === 0) return null;
  return (
    <g className="move-arrows" aria-hidden="true">
      <ArrowHead id={markerId} />
      {arrows.map(({ id, a, b }) => (
        <MoveArrow key={id} id={id} a={a} b={b} markerId={markerId} />
      ))}
    </g>
  );
}

function ArrowHead({ id }: { id: string }) {
  return (
    <defs>
      <marker
        id={id}
        viewBox="0 0 10 10"
        refX="9"
        refY="5"
        markerWidth="6"
        markerHeight="6"
        orient="auto-start-reverse"
      >
        <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor" />
      </marker>
    </defs>
  );
}

function MoveArrow({ id, a, b, markerId }: { id: string; a: Point; b: Point; markerId: string }) {
  return (
    <line
      className="move-arrow"
      data-entity={id}
      x1={a.x}
      y1={a.y}
      x2={b.x}
      y2={b.y}
      stroke="currentColor"
      strokeWidth={1.5}
      strokeDasharray="4 3"
      vectorEffect="non-scaling-stroke"
      markerEnd={`url(#${markerId})`}
    />
  );
}

function MapControls({ view }: { view: ReturnType<typeof useMapView> }) {
  return (
    <div className="map-controls" role="group" aria-label="Map view">
      <button type="button" aria-label="Zoom in" onClick={view.zoomIn}>
        +
      </button>
      <button type="button" aria-label="Zoom out" onClick={view.zoomOut}>
        −
      </button>
      <button type="button" onClick={view.fit} disabled={view.isFitted}>
        Fit
      </button>
    </div>
  );
}
