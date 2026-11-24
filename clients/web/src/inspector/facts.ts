import {
  compactJson,
  exactJson,
  exactNumber,
  exactPoint,
  exactQuantity,
  formatNumber,
  formatPoint,
  formatQuantity,
  type Quantity,
} from '../format/units.ts';
import { boundsOfEntity, polygonOf, sizeOf } from '../components/shapes.ts';
import type { Resource } from '../resources/cache.ts';

/** One row of the entity panel: a label and the value as shown, with the exact value as its tooltip. */
export interface Fact {
  label: string;
  value: string;
  /** The numbers of `value` as the server sent them, when `value` shortens them. */
  title?: string;
}

/** A value as shown and as the server sent it. */
export interface Shown {
  value: string;
  title?: string;
}

/** What a missing value shows as. The client never estimates one. */
export const MISSING = '—';

const NOTHING: Shown = { value: MISSING };

function numberOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function textOf(value: unknown): string {
  return typeof value === 'string' && value !== '' ? value : MISSING;
}

/** A number without a unit (hp), or "—"; 4 significant figures, the exact number as the tooltip. */
export function plainNumber(value: unknown): Shown {
  const n = numberOf(value);
  return n === null ? NOTHING : { value: formatNumber(n), title: exactNumber(n) };
}

/** A physical quantity in the unit that fits it, or "—" when the server didn't send one. */
export function quantityOf(value: unknown, quantity: Quantity): Shown {
  const n = numberOf(value);
  return n === null
    ? NOTHING
    : { value: formatQuantity(n, quantity), title: exactQuantity(n, quantity) };
}

/** The diameter (2 × radius, `docs/resources.md`). */
export function diameterOf(radius: unknown): Shown {
  const r = numberOf(radius);
  return r === null ? NOTHING : quantityOf(2 * r, 'length');
}

/** Whether others can overlap it. `overlap` is always written; absent reads as false. */
export function collisionOf(overlap: unknown): string {
  return overlap === true ? 'others can overlap it' : 'solid: others cannot overlap it';
}

/** A position, scaled like a length. */
export function positionOf(position: unknown): Shown {
  const p = position as { x?: unknown; y?: unknown } | undefined;
  const x = numberOf(p?.x);
  const y = numberOf(p?.y);
  return x === null || y === null ? NOTHING : { value: formatPoint(x, y), title: exactPoint(x, y) };
}

/** Who picks its actions: a controller (`{kind, principal | sub}`), a behaviour, or no one. */
export function controllerOf(entity: Resource): string {
  const controller = entity.controller as Record<string, unknown> | undefined;
  if (controller && typeof controller === 'object') {
    const who = controller.principal ?? controller.sub;
    return `${textOf(controller.kind)} ${textOf(who)}`;
  }
  const behaviour = entity.behaviour as Record<string, unknown> | undefined;
  if (behaviour && typeof behaviour === 'object') return `behaviour: ${textOf(behaviour.kind)}`;
  return MISSING;
}

/** The action in progress, its params and its game times (numbers cut, exact in the tooltip), or "idle". */
export function actionOf(entity: Resource): Shown {
  const action = entity.actionInProgress as Record<string, unknown> | undefined;
  if (!action || typeof action !== 'object') return { value: 'idle' };
  const text = (json: (value: unknown) => string, time: (n: number) => string) => {
    const params = action.params === undefined ? '' : ` ${json(action.params)}`;
    const start = numberOf(action.start);
    const end = numberOf(action.end);
    const span = start === null || end === null ? '' : `, game time ${time(start)} to ${time(end)}`;
    return `${textOf(action.type)}${params}${span}`;
  };
  const value = text(compactJson, (n) => formatQuantity(n, 'time'));
  const exact = text(exactJson, (n) => exactQuantity(n, 'time'));
  return exact === value ? { value } : { value, title: exact };
}

function identityFacts(entity: Resource): Fact[] {
  return [
    { label: 'name', value: textOf(entity.name) },
    { label: 'prototype', value: textOf(entity.prototype) },
    { label: 'kind', value: textOf(entity.entityKind) },
  ];
}

/** What a polygon entity shows instead of a diameter: its material, vertex count and bounding size. */
export function polygonFacts(entity: Resource): Fact[] {
  const polygon = polygonOf(entity);
  const bounds = boundsOfEntity(entity);
  if (!polygon || !bounds) return [];
  const { width, height } = sizeOf(bounds);
  return [
    { label: 'material', value: textOf(entity.material) },
    { label: 'vertices', value: String(polygon.length) },
    sizeFact(width, height),
  ];
}

function sizeFact(width: number, height: number): Fact {
  const w = quantityOf(width, 'length');
  const h = quantityOf(height, 'length');
  return {
    label: 'size',
    value: `${w.value} × ${h.value} (bounding box)`,
    title: `${w.title} × ${h.title}`,
  };
}

function shapeFacts(entity: Resource): Fact[] {
  const polygon = polygonFacts(entity);
  return polygon.length > 0 ? polygon : [{ label: 'diameter', ...diameterOf(entity.radius) }];
}

function physicalFacts(entity: Resource): Fact[] {
  const facts: Fact[] = [
    { label: 'mass', ...quantityOf(entity.mass, 'mass') },
    ...shapeFacts(entity),
    { label: 'collision', value: collisionOf(entity.overlap) },
  ];
  if (entity.damageable === true) facts.push({ label: 'hp', ...plainNumber(entity.hp) });
  facts.push({ label: 'speed', ...quantityOf(entity.speed, 'speed') });
  facts.push({ label: 'position', ...positionOf(entity.position) });
  return facts;
}

/**
 * Where stairs lead: their direction (from the prototype, `stairs-down` or
 * `stairs-up`) and the paired stairs' id, "not made yet" while the level
 * beyond doesn't exist. The area they lead to isn't served yet (#235).
 */
export function stairsFacts(entity: Resource): Fact[] {
  const direction = /^stairs-(up|down)$/.exec(String(entity.prototype ?? ''))?.[1];
  if (!direction) return [];
  return [
    { label: 'leads', value: `${direction} (to another level)` },
    { label: 'paired stairs', value: textOf(entity.to).replace(MISSING, 'not made yet') },
  ];
}

/** The rows of the entity panel, in order (#79). */
export function entityFacts(entity: Resource): Fact[] {
  return [
    ...identityFacts(entity),
    ...stairsFacts(entity),
    ...physicalFacts(entity),
    { label: 'action', ...actionOf(entity) },
    { label: 'controller', value: controllerOf(entity) },
  ];
}

/** The rows of the panel for an area: what it is (it has no size; its walls are its edge). */
export function areaFacts(area: Resource): Fact[] {
  return [
    { label: 'name', value: textOf(area.name) },
    // `depth` is always written; absent reads as 0 (`docs/resources.md`).
    { label: 'depth', ...(area.depth === undefined ? { value: '0' } : plainNumber(area.depth)) },
    { label: 'game time', ...quantityOf(area.time, 'time') },
  ];
}

/** The panel's rows for any resource. */
export function factsOf(resource: Resource): Fact[] {
  return resource.resourceKind === 'area' ? areaFacts(resource) : entityFacts(resource);
}

/** What a resource is called in lists and headings: its name, else its kind, else its id. */
export function nameOf(resource: Resource): string {
  if (typeof resource.name === 'string' && resource.name !== '') return resource.name;
  if (typeof resource.prototype === 'string') return resource.prototype;
  return resource.id;
}
