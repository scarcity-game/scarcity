// Whose turn it is in an area (`docs/actions.md` "Turns"), read from the resources the view holds.
import type { Resource } from '../resources/cache.ts';

export interface OwnEntity {
  entity: Resource;
  /** The area's `activeEntity` is this entity: only now may it act. */
  myTurn: boolean;
}

function controlledBy(entity: Resource | undefined, sub: string): entity is Resource {
  const controller = entity?.controller as { kind?: unknown; sub?: unknown } | undefined;
  return entity?.resourceKind === 'entity' && controller?.kind === 'user' && controller.sub === sub;
}

function activeEntityId(area: Resource): string | undefined {
  const active = area.activeEntity as { entity?: unknown } | undefined;
  return typeof active?.entity === 'string' ? active.entity : undefined;
}

/**
 * The entity of the area that the user `sub` controls; the active one when several are theirs.
 * Null when they have none there (a spectator who is not in the game).
 */
export function ownEntity(
  area: Resource | undefined,
  view: ReadonlyMap<string, Resource>,
  sub: string,
): OwnEntity | null {
  if (area === undefined) return null;
  const own = area.children.map((id) => view.get(id)).filter((e) => controlledBy(e, sub));
  const active = activeEntityId(area);
  const entity = own.find((e) => e?.id === active) ?? own[0];
  return entity === undefined ? null : { entity, myTurn: entity.id === active };
}
