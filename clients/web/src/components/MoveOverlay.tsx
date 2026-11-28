import type { MoveOverlay as Overlay } from '../game/control/useMoveMode.ts';
import { toSvg } from './shapes.ts';

/**
 * Move mode on the map: the entity's step reach as a circle, and the step under the pointer as a
 * line to a ghost of the entity where it would go (at the reach's edge when pulled back). It takes
 * no pointer events; the map's own handlers report the pointer.
 */
export function MoveOverlay({ overlay }: { overlay: Overlay }) {
  const from = toSvg(overlay.from);
  const { reach, preview, radius } = overlay;
  const to = preview && toSvg(preview.to);
  return (
    <g className="move-overlay" pointerEvents="none">
      {reach !== undefined && (
        <circle
          className="move-reach"
          cx={from.x}
          cy={from.y}
          r={reach}
          vectorEffect="non-scaling-stroke"
        />
      )}
      {to && (
        <g className="move-preview" data-pulled-back={preview.pulledBack ? 'true' : undefined}>
          <line x1={from.x} y1={from.y} x2={to.x} y2={to.y} vectorEffect="non-scaling-stroke" />
          <circle cx={to.x} cy={to.y} r={Math.max(radius, 0.1)} vectorEffect="non-scaling-stroke" />
        </g>
      )}
    </g>
  );
}
