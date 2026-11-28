import { useState } from 'react';
import type { Resource } from '../../resources/cache.ts';
import type { OwnEntity } from '../turn.ts';
import { ActionBar } from './ActionBar.tsx';
import { ItemsPanel, type ItemsPanelKind } from './ItemsPanel.tsx';
import { UnitFrame } from './UnitFrame.tsx';

interface Props {
  own: OwnEntity;
  view: ReadonlyMap<string, Resource>;
  /** The map is centred on the own entity. */
  centred: boolean;
  onCentre: () => void;
}

/** The controls for the entity the signed-in user plays: its frame, its actions, its items. */
export function ControlHud({ own, view, centred, onCentre }: Props) {
  const [openPanel, setOpenPanel] = useState<ItemsPanelKind | null>(null);
  const toggle = (kind: ItemsPanelKind) => setOpenPanel((open) => (open === kind ? null : kind));
  return (
    <div className="control-hud">
      {openPanel && <ItemsPanel kind={openPanel} entity={own.entity} view={view} />}
      <div className="control-hud-row">
        <UnitFrame entity={own.entity} myTurn={own.myTurn} centred={centred} onCentre={onCentre} />
        <ActionBar
          entity={own.entity}
          myTurn={own.myTurn}
          openPanel={openPanel}
          onTogglePanel={toggle}
        />
      </div>
    </div>
  );
}
