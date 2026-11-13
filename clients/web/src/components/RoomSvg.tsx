import type { Resource } from '../resources/cache.ts';

interface Point {
  x: number;
  y: number;
}

function pointOf(state: Record<string, unknown>): Point | null {
  const p = state.position as Partial<Point> | undefined;
  return p && typeof p.x === 'number' && typeof p.y === 'number' ? { x: p.x, y: p.y } : null;
}

const PAD = 0.5; // world units around the room inside the viewBox
const MARK = 0.6; // size of a mark (square side / circle diameter), world units

/**
 * The area as an SVG in world units. The world's origin is bottom-left with y
 * up, SVG's is top-left with y down, so every y is drawn at `height - y`.
 */
export function RoomSvg({
  width,
  height,
  entities,
}: {
  width: number;
  height: number;
  entities: Resource[];
}) {
  return (
    <svg
      className="room-svg"
      role="img"
      aria-label="Room"
      viewBox={`${-PAD} ${-PAD} ${width + 2 * PAD} ${height + 2 * PAD}`}
      preserveAspectRatio="xMidYMid meet"
    >
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
      {entities.map((entity) => {
        const pos = pointOf(entity.state);
        if (!pos) return null;
        const x = pos.x;
        const y = height - pos.y;
        const name = typeof entity.state.name === 'string' ? entity.state.name : entity.id;
        const mobile = entity.state.kind === 'mobile';
        const radius =
          typeof entity.state.radius === 'number' && entity.state.radius > 0
            ? entity.state.radius
            : null;
        return (
          <g key={entity.id} data-entity-id={entity.id} data-kind={String(entity.state.kind)}>
            {radius !== null ? (
              <circle cx={x} cy={y} r={radius} fill="currentColor" />
            ) : mobile ? (
              <circle cx={x} cy={y} r={MARK / 2} fill="currentColor" />
            ) : (
              <rect
                x={x - MARK / 2}
                y={y - MARK / 2}
                width={MARK}
                height={MARK}
                fill="currentColor"
              />
            )}
            <text x={x} y={y - MARK} fontSize={0.5} textAnchor="middle" fill="currentColor">
              {name}
            </text>
          </g>
        );
      })}
    </svg>
  );
}
