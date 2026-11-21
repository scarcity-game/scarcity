// The version rule from /AGENTS.md ("Versions detect missed updates"), as pure
// functions. `cached` is the version we hold for the id, undefined if none.

/**
 * - `apply`: it is the next version and is an update: apply its delta.
 * - `fetch`: we missed something (or have no base): GET the resource.
 * - `ignore`: a duplicate or an old message.
 */
export type ChangeDecision = 'apply' | 'fetch' | 'ignore';

export function decideChange(
  cached: number | undefined,
  incoming: number,
  isUpdate: boolean,
): ChangeDecision {
  // A delta without a base can't be applied.
  if (cached === undefined) return 'fetch';
  if (incoming <= cached) return 'ignore';
  if (incoming === cached + 1) return isUpdate ? 'apply' : 'fetch';
  return 'fetch';
}
