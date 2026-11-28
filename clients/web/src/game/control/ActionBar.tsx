import type { Resource } from '../../resources/cache.ts';
import { WaitAction } from '../WaitControl.tsx';
import { actionLabel } from './actionLabels.ts';
import type { ItemsPanelKind } from './ItemsPanel.tsx';

interface Props {
  entity: Resource;
  myTurn: boolean;
  openPanel: ItemsPanelKind | null;
  onTogglePanel: (kind: ItemsPanelKind) => void;
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
  props: { kind: ItemsPanelKind; label: string } & Omit<Props, 'entity' | 'myTurn'>,
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
export function ActionBar({ entity, myTurn, openPanel, onTogglePanel }: Props) {
  return (
    <div className="action-bar" role="toolbar" aria-label="Actions">
      {actionsOf(entity).map((type) =>
        type === 'wait' ? (
          <WaitAction key={type} entity={entity} myTurn={myTurn} />
        ) : (
          <UnsupportedAction key={type} type={type} />
        ),
      )}
      <span className="action-bar-gap" />
      <PanelToggle kind="inventory" label="Inventory" {...{ openPanel, onTogglePanel }} />
      <PanelToggle kind="equipment" label="Equipment" {...{ openPanel, onTogglePanel }} />
    </div>
  );
}
