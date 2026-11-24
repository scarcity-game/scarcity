import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { MaterialPatterns } from '../components/materials.ts';
import { getResource } from '../resources/worldApi.ts';

/**
 * The game's `materialPatterns` (drawn once per game, so read once per page). Undefined until
 * the game arrives and when it can't be read: the walls then use the manifest's defaults.
 */
export function useMaterialPatterns(gameId: string): MaterialPatterns | undefined {
  const { authRequest } = useAuth();
  const [loaded, setLoaded] = useState<{ gameId: string; patterns?: MaterialPatterns }>();
  useEffect(() => {
    const controller = new AbortController();
    getResource(authRequest, gameId, { signal: controller.signal }).then(
      (game) => setLoaded({ gameId, patterns: game?.materialPatterns as MaterialPatterns }),
      () => {},
    );
    return () => controller.abort();
  }, [authRequest, gameId]);
  return loaded?.gameId === gameId ? loaded.patterns : undefined;
}
