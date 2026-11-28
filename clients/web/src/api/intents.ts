// Intents (`docs/actions.md` "Intents"): a controller's choice of the next action for its resource.
import { ApiError, errorMessage } from './client.ts';
import type { Request } from '../resources/worldApi.ts';

/** The 200 answer: the area, its game time, the log head, and the entity's version after the advance. */
export interface IntentResult {
  area: string;
  time: number;
  logSeq: number;
  version: number;
}

/** Posts `wait` for `entity`, made on `ifVersion` of it; the area advances by one action. */
export function postWait(
  request: Request,
  entity: string,
  duration: number,
  ifVersion: number,
): Promise<IntentResult> {
  return request<IntentResult>('/api/world/intents', {
    method: 'POST',
    body: { entity, actionSpec: { type: 'wait', params: { duration } }, ifVersion },
  });
}

/** Posts `step` for `entity` to the world point `to`, made on `ifVersion` of it. */
export function postStep(
  request: Request,
  entity: string,
  to: { x: number; y: number },
  ifVersion: number,
): Promise<IntentResult> {
  return request<IntentResult>('/api/world/intents', {
    method: 'POST',
    body: { entity, actionSpec: { type: 'step', params: { to } }, ifVersion },
  });
}

/** The server's `{"error": "…"}` of a refused intent, else the client's usual wording. */
export function intentErrorMessage(err: unknown): string {
  const text = err instanceof ApiError ? err.problem?.error : undefined;
  return typeof text === 'string' && text !== '' ? text : errorMessage(err);
}
