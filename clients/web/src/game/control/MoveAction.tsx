import { formatRoughQuantity } from '../../format/units.ts';
import type { Resource } from '../../resources/cache.ts';
import { resourceName } from '../../views/GamesView.tsx';
import type { StepPreview } from './stepPreview.ts';
import type { MoveMode } from './useMoveMode.ts';

/** "2.9 m, 2.9 s", and "(the reach)" when pulled back to it. */
export function previewText(preview: StepPreview): string {
  const { distance, duration, pulledBack } = preview;
  const time = Number.isNaN(duration) ? '' : `, ${formatRoughQuantity(duration, 'time')}`;
  const edge = pulledBack ? ' (the reach)' : '';
  return `${formatRoughQuantity(distance, 'length')}${time}${edge}`;
}

/** What move mode tells under its button: how to use it, or the step under the pointer. */
function hintOf(move: MoveMode, myTurn: boolean): string {
  if (!myTurn) return 'Waiting for your turn';
  const preview = move.overlay?.preview;
  return preview ? previewText(preview) : 'Click the map or use the arrow keys; Esc to stop';
}

/** The action bar's `step`: Move toggles move mode, which the map and the arrow keys then use. */
export function MoveAction({
  entity,
  myTurn,
  move,
}: {
  entity: Resource;
  myTurn: boolean;
  move: MoveMode;
}) {
  return (
    <div className="move-control" role="group" aria-label="Move">
      <button type="button" aria-pressed={move.active} onClick={move.toggle}>
        Move
      </button>
      {move.active && (
        <span className="move-hint" role="status">
          {hintOf(move, myTurn)}
        </span>
      )}
      {move.error && (
        <span className="form-error" role="alert">
          {resourceName(entity)}: {move.error}
        </span>
      )}
    </div>
  );
}
