import type { Resource } from './cache.ts';
import { listChildrenOfType, type Request } from './worldApi.ts';

/** The entity a spectator follows: the game's player, or failing that its npc. */
export function followedEntity(entities: readonly Resource[]): Resource | undefined {
  return (
    entities.find((e) => e.entityKind === 'player') ?? entities.find((e) => e.entityKind === 'npc')
  );
}

/**
 * The area a spectator should watch: the one holding the game's player, else
 * its npc. Undefined when no area holds either (the entity may be between
 * areas). Reads the first page of each area's entities.
 */
export async function findFollowedArea(
  request: Request,
  areas: readonly Resource[],
  signal?: AbortSignal,
): Promise<Resource | undefined> {
  const entityLists = await Promise.all(
    areas.map((area) => listChildrenOfType(request, area.id, 'entity', signal)),
  );
  const pick = (kind: string) =>
    areas.find((_, i) => entityLists[i]?.some((e) => e.entityKind === kind));
  return pick('player') ?? pick('npc');
}
