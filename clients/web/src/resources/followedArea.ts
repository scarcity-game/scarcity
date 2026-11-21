import type { Resource } from './cache.ts';
import { findGameEntity, listAllChildren, type Request } from './worldApi.ts';

/**
 * The area of the game whose children hold `entityId`; undefined while no area does (the entity
 * may be between areas, or gone).
 */
export async function findEntityArea(
  request: Request,
  gameId: string,
  entityId: string,
  signal?: AbortSignal,
): Promise<Resource | undefined> {
  const areas = await listAllChildren(request, gameId, signal);
  return areas.find((a) => a.children.includes(entityId));
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
