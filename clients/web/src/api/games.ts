// New games (`docs/resources.md`, "New games"): the profiles a game can be made from, and making one.
import type { Request } from '../resources/worldApi.ts';

export interface Mob {
  prototype: string;
  name: string;
  faction: string;
}

export interface ArenaOptions {
  mobs: Mob[];
  maxDensPerMob: number;
}

export interface Profile {
  name: string;
  options: ArenaOptions;
}

export interface Den {
  prototype: string;
  count: number;
}

export interface NewGame {
  game: string;
  area: string;
  entity: string;
}

export async function listProfiles(request: Request, signal?: AbortSignal): Promise<Profile[]> {
  const body = await request<{ profiles: Profile[] }>('/api/world/games/profiles', { signal });
  return body.profiles;
}

export function createGame(request: Request, profile: string, dens: Den[]): Promise<NewGame> {
  return request<NewGame>('/api/world/games', {
    method: 'POST',
    body: { profile, options: { dens } },
  });
}
