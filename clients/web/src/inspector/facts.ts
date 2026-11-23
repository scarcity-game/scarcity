import { boundsOfEntity, polygonOf, sizeOf } from '../components/shapes.ts';
import type { Resource } from '../resources/cache.ts';

/** One row of the entity panel: a label and the value as shown. */
export interface Fact {
  label: string;
  value: string;
}

/** What a missing value shows as. The client never estimates one. */
export const MISSING = '—';

/**
 * A number as shown: the server's value, with floating-point noise past 12
 * significant digits dropped (6.999999999999999 shows as 7). Not rounded further.
 */
export function formatNumber(value: number): string {
  return String(Number(value.toPrecision(12)));
}

function numberOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function textOf(value: unknown): string {
  return typeof value === 'string' && value !== '' ? value : MISSING;
}

/** A number without a unit (hp), or "—". */
export function plainNumber(value: unknown): string {
  const n = numberOf(value);
  return n === null ? MISSING : formatNumber(n);
}

/** A number with its SI unit, or "—" when the server didn't send one. */
export function withUnit(value: unknown, unit: string): string {
  const n = numberOf(value);
  return n === null ? MISSING : `${formatNumber(n)} ${unit}`;
}

/** The diameter (2 × radius, `docs/resources.md`), in metres. */
export function diameterOf(radius: unknown): string {
  const r = numberOf(radius);
  return r === null ? MISSING : withUnit(2 * r, 'm');
}

/** Whether others can overlap it. `overlap` is always written; absent reads as false. */
export function collisionOf(overlap: unknown): string {
  return overlap === true ? 'others can overlap it' : 'solid: others cannot overlap it';
}

/** A position in metres. */
export function positionOf(position: unknown): string {
  const p = position as { x?: unknown; y?: unknown } | undefined;
  const x = numberOf(p?.x);
  const y = numberOf(p?.y);
  return x === null || y === null ? MISSING : `(${formatNumber(x)}, ${formatNumber(y)}) m`;
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

/** The action in progress, its params as the server sent them and its game times, or "idle". */
export function actionOf(entity: Resource): string {
  const action = entity.actionInProgress as Record<string, unknown> | undefined;
  if (!action || typeof action !== 'object') return 'idle';
  const params = action.params === undefined ? '' : ` ${JSON.stringify(action.params)}`;
  const start = numberOf(action.start);
  const end = numberOf(action.end);
  const span =
    start === null || end === null
      ? ''
      : `, game time ${formatNumber(start)} to ${formatNumber(end)}`;
  return `${textOf(action.type)}${params}${span}`;
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
    { label: 'size', value: `${formatNumber(width)} × ${formatNumber(height)} m (bounding box)` },
  ];
}

function shapeFacts(entity: Resource): Fact[] {
  const polygon = polygonFacts(entity);
  return polygon.length > 0 ? polygon : [{ label: 'diameter', value: diameterOf(entity.radius) }];
}

function physicalFacts(entity: Resource): Fact[] {
  const facts: Fact[] = [
    { label: 'mass', value: withUnit(entity.mass, 'g') },
    ...shapeFacts(entity),
    { label: 'collision', value: collisionOf(entity.overlap) },
  ];
  if (entity.damageable === true) facts.push({ label: 'hp', value: plainNumber(entity.hp) });
  facts.push({ label: 'speed', value: withUnit(entity.speed, 'm/s') });
  facts.push({ label: 'position', value: positionOf(entity.position) });
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
    { label: 'action', value: actionOf(entity) },
    { label: 'controller', value: controllerOf(entity) },
  ];
}

/** The rows of the panel for an area: what it is and its size. */
export function areaFacts(area: Resource): Fact[] {
  const width = numberOf(area.width);
  const height = numberOf(area.height);
  return [
    { label: 'name', value: textOf(area.name) },
    // `depth` is always written; absent reads as 0 (`docs/resources.md`).
    { label: 'depth', value: area.depth === undefined ? '0' : plainNumber(area.depth) },
    {
      label: 'size',
      value:
        width === null || height === null
          ? MISSING
          : `${formatNumber(width)} × ${formatNumber(height)} m`,
    },
    { label: 'game time', value: withUnit(area.time, 's') },
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
