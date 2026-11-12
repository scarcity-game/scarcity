// The version rule from /AGENTS.md ("Versions detect missed updates"), as pure
// functions. `cached` is the version we hold for the id, undefined if none.

/**
 * - `apply`: it is the next version and carries a patch: replace the state.
 * - `fetch`: we missed something (or have no base): GET the resource.
 * - `ignore`: a duplicate or an old message.
 */
export type ChangeDecision = 'apply' | 'fetch' | 'ignore';

export function decideChange(
  cached: number | undefined,
  incoming: number,
  hasPatch: boolean,
): ChangeDecision {
  // A patch without a base is not trusted.
  if (cached === undefined) return 'fetch';
  if (incoming <= cached) return 'ignore';
  if (incoming === cached + 1) return hasPatch ? 'apply' : 'fetch';
  return 'fetch';
}

/** A digest entry: higher than cached (or not cached at all) means GET. */
export function decideDigestEntry(cached: number | undefined, server: number): 'fetch' | 'ignore' {
  if (cached === undefined) return 'fetch';
  return server > cached ? 'fetch' : 'ignore';
}
