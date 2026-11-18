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

/** The label for an entity: its name, else its kind, else the end of its id. */
function labelOf(entity: Resource): string {
  if (typeof entity.name === 'string') return entity.name;
  if (typeof entity.entityKind === 'string') return entity.entityKind;
  // Use last 4 characters of ID if no name or kind
  return entity.id.slice(-4);
}

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

function Rings({
  x,
  y,
  r,
  highlighted,
  acting,
}: {
  x: number;
  y: number;
  r: number;
  highlighted: boolean;
  acting: boolean;
}) {
  return (
    <>
      {acting && <Ring x={x} y={y} r={r + 0.15} className="acting-ring" strokeWidth={2} />}
      {highlighted && (
        <Ring x={x} y={y} r={r + 0.25} className="highlight-ring" strokeWidth={3} dashed />
      )}
    </>
  );
}

interface EntityMarkProps {
  entity: Resource;
  pos: Point;
  width: number;
  height: number;
  highlighted: boolean;
  acting: boolean;
}

function EntityMark({ entity, pos, width, height, highlighted, acting }: EntityMarkProps) {
  const { x, y } = toSvg(pos, width, height);
  const radius = radiusOf(entity);
  const ringRadius = radius ?? MARK / 2;
  return (
    <g
      data-entity-id={entity.id}
      data-kind={String(entity.entityKind)}
      className={highlighted ? 'highlighted' : undefined}
      data-acting={acting ? 'true' : undefined}
    >
      <Rings x={x} y={y} r={ringRadius} highlighted={highlighted} acting={acting} />
      <Mark x={x} y={y} radius={radius} mobile={isMobile(entity)} />
      <text x={x} y={y - MARK} fontSize={0.5} textAnchor="middle" fill="currentColor">
        {labelOf(entity)}
      </text>
    </g>
  );
}

interface RoomSvgProps {
  width: number;
  height: number;
  entities: Resource[];
  /** The entity a log entry under the pointer is about; drawn with a ring. */
  highlightId?: string | null;
  /** Entities whose action is playing in the beat on screen; each gets a ring. */
  acting?: ReadonlySet<string>;
}

/**
 * The area as an SVG in world units. The world's origin is bottom-left with y
 * up, SVG's is top-left with y down, so every y is drawn at `height - y`.
 */
export function RoomSvg({ width, height, entities, highlightId = null, acting }: RoomSvgProps) {
  return (
    <svg
      className="room-svg"
      role="img"
      aria-label="Room"
      viewBox={`${-PAD} ${-PAD} ${width + 2 * PAD} ${height + 2 * PAD}`}
      preserveAspectRatio="xMidYMid meet"
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
            width={width}
            height={height}
            highlighted={entity.id === highlightId}
            acting={acting?.has(entity.id) ?? false}
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
