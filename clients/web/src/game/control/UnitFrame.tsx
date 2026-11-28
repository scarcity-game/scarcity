import { formatNumber, formatQuantity } from '../../format/units.ts';
import type { Resource } from '../../resources/cache.ts';
import { SpriteIcon } from '../../sprites/Sprite.tsx';
import { resourceName } from '../../views/GamesView.tsx';

interface Props {
  entity: Resource;
  myTurn: boolean;
  /** The map is centred on this entity. */
  centred: boolean;
  onCentre: () => void;
}

interface Stat {
  label: string;
  value: string;
}

function numberOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

/** HP (when damageable), speed, step reach and vision; each only when the entity has it. */
export function unitStats(entity: Resource): Stat[] {
  const stats: Stat[] = [];
  const hp = numberOf(entity.hp);
  if (entity.damageable === true && hp !== undefined)
    stats.push({ label: 'HP', value: formatNumber(hp) });
  const lengths: [string, unknown, 'speed' | 'length'][] = [
    ['Speed', entity.speed, 'speed'],
    ['Reach', entity.stepReach, 'length'],
    ['Vision', entity.vision, 'length'],
  ];
  for (const [label, raw, quantity] of lengths) {
    const n = numberOf(raw);
    if (n !== undefined) stats.push({ label, value: formatQuantity(n, quantity) });
  }
  return stats;
}

/** The entity the signed-in user controls: its sprite, name, whose turn it is and its numbers. */
export function UnitFrame({ entity, myTurn, centred, onCentre }: Props) {
  return (
    <section className="unit-frame" aria-label="Your entity" data-turn={myTurn ? 'yours' : 'other'}>
      <SpriteIcon entity={entity} size={48} />
      <div className="unit-frame-body">
        <div className="unit-frame-title">
          <strong>{resourceName(entity)}</strong>
          <span role="status">{myTurn ? 'Your turn' : 'Not your turn'}</span>
        </div>
        <dl className="unit-stats">
          {unitStats(entity).map((s) => (
            <div key={s.label}>
              <dt>{s.label}</dt>
              <dd>{s.value}</dd>
            </div>
          ))}
        </dl>
      </div>
      <button type="button" aria-pressed={centred} onClick={onCentre}>
        Centre
      </button>
    </section>
  );
}
