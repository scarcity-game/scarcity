import type { Resource } from '../../resources/cache.ts';
import { WaitAction } from '../WaitControl.tsx';
import { actionLabel } from './actionLabels.ts';
import type { ItemsPanelKind } from './ItemsPanel.tsx';
import { MoveAction } from './MoveAction.tsx';
import type { MoveMode } from './useMoveMode.ts';

interface Props {
  entity: Resource;
  myTurn: boolean;
  openPanel: ItemsPanelKind | null;
  onTogglePanel: (kind: ItemsPanelKind) => void;
  move: MoveMode;
}

/** The action types the entity has, in its own order (`docs/resources.md`, `actions`). */
export function actionsOf(entity: Resource): string[] {
  const actions = entity.actions;
  return Array.isArray(actions) ? actions.filter((a): a is string => typeof a === 'string') : [];
}

/** An action the client has no input for yet: shown, so the player sees what the entity can do. */
function UnsupportedAction({ type }: { type: string }) {
  return (
    <button type="button" disabled title="Not available from the web client yet">
      {actionLabel(type)}
    </button>
  );
}

function PanelToggle(
  props: { kind: ItemsPanelKind; label: string } & Pick<Props, 'openPanel' | 'onTogglePanel'>,
) {
  const { kind, label, openPanel, onTogglePanel } = props;
  return (
    <button type="button" aria-expanded={openPanel === kind} onClick={() => onTogglePanel(kind)}>
      {label}
    </button>
  );
}

/**
 * One control per action type the entity has, then Inventory and Equipment. An action is usable
 * only on the entity's turn; the server decides whether it is valid.
 */
function Action({ type, entity, myTurn, move }: { type: string } & Props) {
  if (type === 'step') return <MoveAction entity={entity} myTurn={myTurn} move={move} />;
  if (type === 'wait') return <WaitAction entity={entity} myTurn={myTurn} />;
  return <UnsupportedAction type={type} />;
}

export function ActionBar(props: Props) {
  const { entity, openPanel, onTogglePanel } = props;
  return (
    <div className="action-bar" role="toolbar" aria-label="Actions">
      {actionsOf(entity).map((type) => (
        <Action key={type} type={type} {...props} />
      ))}
      <span className="action-bar-gap" />
      <PanelToggle kind="inventory" label="Inventory" {...{ openPanel, onTogglePanel }} />
      <PanelToggle kind="equipment" label="Equipment" {...{ openPanel, onTogglePanel }} />
    </div>
  );
}
