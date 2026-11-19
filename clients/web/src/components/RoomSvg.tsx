import type { KeyboardEvent, MouseEvent } from 'react';
import { nameOf } from '../inspector/facts.ts';
import type { Resource } from '../resources/cache.ts';

interface Point {
  x: number;
  y: number;
}

function pointOf(state: Resource): Point | null {
  const p = state.position as Partial<Point> | undefined;
  return p && typeof p.x === 'number' && typeof p.y === 'number' ? { x: p.x, y: p.y } : null;
}

const PAD = 0.5; // world units around the room inside the viewBox
const MARK = 0.6; // size of a mark (square side / circle diameter), world units

function radiusOf(entity: Resource): number | null {
  return typeof entity.radius === 'number' && entity.radius > 0 ? entity.radius : null;
}

function isMobile(entity: Resource): boolean {
  return ['mob', 'npc', 'player'].includes(String(entity.entityKind));
}

/** Clamps a world position to the room bounds and flips y for SVG. */
function toSvg(pos: Point, width: number, height: number): Point {
  return {
    x: Math.max(0, Math.min(width, pos.x)),
    y: height - Math.max(0, Math.min(height, pos.y)),
  };
}

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

function Mark({
  x,
  y,
  radius,
  mobile,
}: {
  x: number;
  y: number;
  radius: number | null;
  mobile: boolean;
}) {
  if (radius !== null) return <circle cx={x} cy={y} r={radius} fill="currentColor" />;
  if (mobile) return <circle cx={x} cy={y} r={MARK / 2} fill="currentColor" />;
  return <rect x={x - MARK / 2} y={y - MARK / 2} width={MARK} height={MARK} fill="currentColor" />;
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
  room: { width: number; height: number };
  flags: RingFlags;
  events: RoomEvents;
}

/** A mark: no label (#79); its name is its accessible name, and the entity panel shows the rest. */
function EntityMark({ entity, pos, room, flags, events }: EntityMarkProps) {
  const { x, y } = toSvg(pos, room.width, room.height);
  const radius = radiusOf(entity);
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
      <Rings x={x} y={y} r={radius ?? MARK / 2} flags={flags} />
      <Mark x={x} y={y} radius={radius} mobile={isMobile(entity)} />
    </g>
  );
}

interface RoomSvgProps extends RoomEvents {
  width: number;
  height: number;
  entities: Resource[];
  /** The entity a log entry under the pointer is about; drawn with a ring. */
  highlightId?: string | null;
  /** The selected entity; drawn with a ring. */
  selectedId?: string | null;
  /** Entities whose action is playing in the beat on screen; each gets a ring. */
  acting?: ReadonlySet<string>;
}

function flagsOf(id: string, { highlightId, selectedId, acting }: RoomSvgProps): RingFlags {
  return {
    highlighted: id === highlightId,
    selected: id === selectedId,
    acting: acting?.has(id) ?? false,
  };
}

/**
 * The area as an SVG in world units. The world's origin is bottom-left with y
 * up, SVG's is top-left with y down, so every y is drawn at `height - y`.
 * A click on a mark selects it; a click on the empty map clears the selection.
 */
export function RoomSvg(props: RoomSvgProps) {
  const { width, height, entities, onSelect, onHover } = props;
  const events: RoomEvents = { onSelect, onHover };
  return (
    <svg
      className="room-svg"
      role="img"
      aria-label="Room"
      viewBox={`${-PAD} ${-PAD} ${width + 2 * PAD} ${height + 2 * PAD}`}
      preserveAspectRatio="xMidYMid meet"
      onClick={() => onSelect?.(null)}
    >
      <RoomBorder width={width} height={height} />
      {entities.map((entity) => {
        const pos = pointOf(entity);
        if (!pos) return null;
        return (
          <EntityMark
            key={entity.id}
            entity={entity}
            pos={pos}
            room={{ width, height }}
            flags={flagsOf(entity.id, props)}
            events={events}
          />
        );
      })}
    </svg>
  );
}

function RoomBorder({ width, height }: { width: number; height: number }) {
  return (
    <rect
      className="room-border"
      x={0}
      y={0}
      width={width}
      height={height}
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      vectorEffect="non-scaling-stroke"
    />
  );
}
