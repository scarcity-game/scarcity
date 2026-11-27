import type { ListedResource } from './cache.ts';
import { findAreaHolding, findGameEntity, type Request } from './worldApi.ts';

/**
 * The area a spectator should watch: the one holding the game's player, else
 * its npc. Undefined when no area holds either (the entity may be between
 * areas). Asks the world for the game's entity of each kind (`game=` filter,
 * not a request per area), then for the area holding it (`child=`).
 */
export async function findFollowedArea(
  request: Request,
  gameId: string,
  signal?: AbortSignal,
): Promise<ListedResource | undefined> {
  for (const kind of ['player', 'npc']) {
    const entity = await findGameEntity(request, gameId, kind, signal);
    const area = entity && (await findAreaHolding(request, gameId, entity.id, signal));
    if (area) return area;
  }
  return undefined;
}
