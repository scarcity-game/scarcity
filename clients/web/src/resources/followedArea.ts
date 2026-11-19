import type { Resource } from './cache.ts';
import { findGameEntity, type Request } from './worldApi.ts';

/** The entity a spectator follows: the game's player, or failing that its npc. */
export function followedEntity(entities: readonly Resource[]): Resource | undefined {
  return (
    entities.find((e) => e.entityKind === 'player') ?? entities.find((e) => e.entityKind === 'npc')
  );
}

/**
 * The area a spectator should watch: the one holding the game's player, else
 * its npc. Undefined when no area holds either (the entity may be between
 * areas). Asks the world once per kind for the game's entity (`game=` filter,
 * not a request per area) and finds its area in `areas`, whose children list it.
 */
export async function findFollowedArea(
  request: Request,
  gameId: string,
  areas: readonly Resource[],
  signal?: AbortSignal,
): Promise<Resource | undefined> {
  for (const kind of ['player', 'npc']) {
    const entity = await findGameEntity(request, gameId, kind, signal);
    const area = entity && areas.find((a) => a.children.includes(entity.id));
    if (area) return area;
  }
  return undefined;
}
