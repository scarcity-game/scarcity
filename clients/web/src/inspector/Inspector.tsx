import type { Resource } from '../resources/cache.ts';
import { EntityPanel } from './EntityPanel.tsx';
import { nameOf } from './facts.ts';

/** The resource whose children list holds `id`, if it is loaded. */
export function parentOf(
  id: string,
  resources: ReadonlyMap<string, Resource>,
): Resource | undefined {
  for (const r of resources.values()) if (r.children.includes(id)) return r;
  return undefined;
}

function ChildList({
  parent,
  resources,
  onSelect,
}: {
  parent: Resource;
  resources: ReadonlyMap<string, Resource>;
  onSelect: (id: string) => void;
}) {
  if (parent.children.length === 0) return null;
  return (
    <ul className="inspector-children" aria-label={`In ${nameOf(parent)}`}>
      {parent.children.map((id) => {
        const child = resources.get(id);
        return (
          <li key={id} data-resource-id={id}>
            <button type="button" onClick={() => onSelect(id)}>
              {child ? nameOf(child) : id}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

function UpLink({
  parent,
  onSelect,
}: {
  parent: Resource | undefined;
  onSelect: (id: string | null) => void;
}) {
  if (!parent) return null;
  const up = parent.resourceKind === 'area' ? null : parent.id;
  return (
    <button type="button" className="inspector-up" onClick={() => onSelect(up)}>
      Up to {nameOf(parent)}
    </button>
  );
}

interface InspectorProps {
  /** The selected resource; null or not loaded shows `area`. */
  selected: Resource | undefined;
  area: Resource | undefined;
  resources: ReadonlyMap<string, Resource>;
  onSelect: (id: string | null) => void;
  isStale: (id: string) => boolean;
}

/**
 * The docked panel (#79, docked by #80): the selected resource in the entity
 * panel, with a way up to its parent and down to its children. With nothing
 * selected it shows the area and its entities.
 */
export function Inspector({ selected, area, resources, onSelect, isStale }: InspectorProps) {
  const shown = selected ?? area;
  return (
    <aside className="area-panel inspector" aria-label="Inspector">
      <h2>Inspector</h2>
      {selected && <UpLink parent={parentOf(selected.id, resources)} onSelect={onSelect} />}
      {shown ? (
        <>
          <EntityPanel resource={shown} stale={isStale(shown.id)} />
          <ChildList parent={shown} resources={resources} onSelect={onSelect} />
        </>
      ) : (
        <p>Loading…</p>
      )}
    </aside>
  );
}
