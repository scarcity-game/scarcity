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
  highlightId = null,
}: {
  width: number;
  height: number;
  entities: Resource[];
  /** The entity a log entry under the pointer is about; drawn with a ring. */
  highlightId?: string | null;
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

        // Clamp position to room bounds
        let clampedX = Math.max(0, Math.min(width, pos.x));
        let clampedY = Math.max(0, Math.min(height, pos.y));
        const x = clampedX;
        const y = height - clampedY;

        // Determine label text for unnamed entities
        let name: string;
        if (typeof entity.state.name === 'string') {
          name = entity.state.name;
        } else if (typeof entity.state.kind === 'string') {
          name = entity.state.kind;
        } else {
          // Use last 4 characters of ID if no name or kind
          name = entity.id.slice(-4);
        }

        const mobile = entity.state.kind === 'mobile';
        const radius =
          typeof entity.state.radius === 'number' && entity.state.radius > 0
            ? entity.state.radius
            : null;
        return (
          <g
            key={entity.id}
            data-entity-id={entity.id}
            data-kind={String(entity.state.kind)}
            className={entity.id === highlightId ? 'highlighted' : undefined}
          >
            {entity.id === highlightId && (
              <circle
                className="highlight-ring"
                cx={x}
                cy={y}
                r={(radius ?? MARK / 2) + 0.25}
                fill="none"
                stroke="currentColor"
                strokeWidth={3}
                strokeDasharray="4 3"
                vectorEffect="non-scaling-stroke"
              />
            )}
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
