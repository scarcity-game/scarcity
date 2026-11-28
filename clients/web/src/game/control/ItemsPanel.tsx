import { formatNumber, formatQuantity } from '../../format/units.ts';
import type { Resource } from '../../resources/cache.ts';
import { resourceName } from '../../views/GamesView.tsx';

export type ItemsPanelKind = 'inventory' | 'equipment';

interface Props {
  kind: ItemsPanelKind;
  entity: Resource;
  view: ReadonlyMap<string, Resource>;
}

/** The entity's item children that the view holds, in its order. */
export function carriedItems(entity: Resource, view: ReadonlyMap<string, Resource>): Resource[] {
  return entity.children
    .map((id) => view.get(id))
    .filter((r): r is Resource => r?.resourceKind === 'item');
}

/** "3 ×" for a count item, its mass otherwise. */
function amountOf(item: Resource): string {
  if (typeof item.count === 'number') return `${formatNumber(item.count)} ×`;
  return typeof item.mass === 'number' ? formatQuantity(item.mass, 'mass') : '';
}

function Inventory({ entity, view }: Omit<Props, 'kind'>) {
  const items = carriedItems(entity, view);
  if (items.length === 0) return <p className="items-empty">Carrying nothing.</p>;
  return (
    <ul className="items-list">
      {items.map((item) => (
        <li key={item.id}>
          <span className="item-amount">{amountOf(item)}</span> {resourceName(item)}
        </li>
      ))}
    </ul>
  );
}

/**
 * What the entity carries, or what it wears. Equipment has no slots in the rules yet (#520), so
 * that panel is always empty for now.
 */
export function ItemsPanel({ kind, entity, view }: Props) {
  const title = kind === 'inventory' ? 'Inventory' : 'Equipment';
  return (
    <section className="items-panel" aria-label={title}>
      <h3>{title}</h3>
      {kind === 'inventory' ? (
        <Inventory entity={entity} view={view} />
      ) : (
        <p className="items-empty">Nothing equipped.</p>
      )}
    </section>
  );
}
