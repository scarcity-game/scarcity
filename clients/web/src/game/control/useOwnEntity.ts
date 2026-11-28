import { useEffect, useRef } from 'react';
import { useAuth } from '../../auth/AuthProvider.tsx';
import type { Resource } from '../../resources/cache.ts';
import type { Following } from '../../views/useFollowing.ts';
import { ownEntity, type OwnEntity } from '../turn.ts';

/**
 * The signed-in user's entity in the area, and the map following it: the first time this page sees
 * it (a new id, as after stairs, counts again). A pan stops that as any following; Centre resumes.
 */
export function useOwnEntity(
  area: Resource | undefined,
  view: ReadonlyMap<string, Resource>,
  following: Following,
): OwnEntity | null {
  const { user } = useAuth();
  const own = user === null ? null : ownEntity(area, view, user.id);
  const id = own?.entity.id;
  const followed = useRef<string | undefined>(undefined);
  const { follow } = following;
  useEffect(() => {
    if (id === undefined || followed.current === id) return;
    followed.current = id;
    follow(id);
  }, [id, follow]);
  return own;
}
