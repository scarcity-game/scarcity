// Pure rules: which sprite an entity gets, which state it shows, which frame and how big.
import type { Resource } from '../resources/cache.ts';
import type { Anchor, Overlay, SpriteManifest, SpriteState, SpriteVariant, StateFrames } from './manifest.ts';

/** What an entity is drawn as: a variant's sprite, or a plain disc. */
export type Look =
  { kind: 'sprite'; category: string; variant: SpriteVariant } | { kind: 'disc'; color: string };

/** Colour of the disc for a category the manifest does not know (or no prototype at all). */
export const NEUTRAL_COLOR = '#8a8a8a';

/** A prototype that has no sprite on purpose (the client draws the arriving entity's own instead). */
const NO_SPRITE: ReadonlySet<string> = new Set(['placeholder']);

/** The smallest radius a sprite is drawn with (world units, m): a pebble (0.015 m) stays visible. */
export const MIN_RADIUS = 0.15;

/** The drawn radius: the entity's own, never below `MIN_RADIUS`; an entity without a radius gets the minimum. */
export function drawnRadius(radius: unknown): number {
  return typeof radius === 'number' && radius > MIN_RADIUS ? radius : MIN_RADIUS;
}

/** The sprite grid is 32 units wide, its circle of radius 16 is the entity's radius. */
export function spriteScale(radius: unknown, grid = 32): number {
  return drawnRadius(radius) / (grid / 2);
}

function prototypeOf(entity: Resource): string | null {
  return typeof entity.prototype === 'string' && entity.prototype !== '' ? entity.prototype : null;
}

/**
 * What an entity looks like. A prototype with a variant is its sprite. One without is a disc in
 * the colour of its category, when it names one (`prototype: "humanoid"`), else a neutral disc.
 * `gap` is called with the reason for each fallback, so the caller can report it.
 */
export function lookOf(
  entity: Resource,
  manifest: SpriteManifest,
  gap: (reason: string) => void = () => {},
): Look {
  const prototype = prototypeOf(entity);
  const variant = prototype === null ? undefined : manifest.variants[prototype];
  if (variant !== undefined && manifest.categories[variant.category] !== undefined) {
    return { kind: 'sprite', category: variant.category, variant };
  }
  return { kind: 'disc', color: discColor(prototype, variant, manifest, gap) };
}

function discColor(
  prototype: string | null,
  variant: SpriteVariant | undefined,
  manifest: SpriteManifest,
  gap: (reason: string) => void,
): string {
  if (prototype !== null && NO_SPRITE.has(prototype)) return NEUTRAL_COLOR;
  if (variant !== undefined) {
    gap(`unknown sprite category "${variant.category}" (prototype "${prototype}")`);
    return NEUTRAL_COLOR;
  }
  const named = prototype === null ? undefined : manifest.categories[prototype];
  if (named !== undefined) {
    gap(`no sprite variant for prototype "${prototype}"; drawing its category's disc`);
    return named.fallbackColor;
  }
  gap(`no sprite variant for prototype "${prototype ?? ''}"; drawing a neutral disc`);
  return NEUTRAL_COLOR;
}

const MOVING_ACTIONS: ReadonlySet<string> = new Set(['step', 'fly']);
const IDLE_ACTIONS: ReadonlySet<string> = new Set(['wait', 'decay', 'arrive']);

/**
 * The state an entity shows: `acted-upon` when an effect of the beat on screen has it as its
 * target (from the log), else `moving` for a `step` or `fly` in progress, `acting` for any
 * other action that is not a wait, else `idle` (from `actionInProgress`). A category without
 * the state shows `idle`, or its only state (`static`).
 */
export function stateOf(
  entity: Resource,
  actedUpon: ReadonlyMap<string, string> | undefined,
  states: Partial<Record<SpriteState, unknown>>,
): SpriteState {
  const wanted = wantedState(entity, actedUpon);
  if (wanted in states) return wanted;
  return 'idle' in states ? 'idle' : 'static';
}

function wantedState(entity: Resource, actedUpon: ReadonlyMap<string, string> | undefined): SpriteState {
  if (actedUpon?.has(entity.id)) return 'acted-upon';
  const action = entity.actionInProgress as { type?: unknown } | undefined;
  const type = typeof action?.type === 'string' ? action.type : null;
  if (type === null || IDLE_ACTIONS.has(type)) return 'idle';
  return MOVING_ACTIONS.has(type) ? 'moving' : 'acting';
}

/** The frame (index into `frames`) showing at `clockMs`; `phaseMs` shifts one entity against the others. */
export function frameIndex(state: StateFrames, clockMs: number, phaseMs = 0): number {
  const count = state.frames.length;
  if (count <= 1 || state.frameMs <= 0) return 0;
  return Math.floor((clockMs + phaseMs) / state.frameMs) % count;
}

/** A stable offset in `[0, spanMs)` from an id, so entities of one category do not step in unison. */
export function phaseOf(id: string, spanMs: number): number {
  let hash = 0;
  for (const ch of id) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return hash % Math.max(1, spanMs);
}

/** Where an overlay goes on a frame with these anchors: its symbol and SVG transform, or null when it hangs on an anchor the frame lacks. */
export function overlayPlacement(
  overlay: Overlay,
  anchors: Readonly<Record<string, Anchor>>,
): { symbol: string; transform: string | undefined } | null {
  if (typeof overlay === 'string') return { symbol: overlay, transform: undefined };
  const a = anchors[overlay.anchor];
  if (a === undefined) return null;
  const deg = (a[2] ?? 0) - (overlay.at[2] ?? 0);
  const turn = deg === 0 ? '' : ` rotate(${deg})`;
  return {
    symbol: overlay.symbol,
    transform: `translate(${a[0]} ${a[1]})${turn} translate(${-overlay.at[0]} ${-overlay.at[1]})`,
  };
}

/** Where an effect's icon goes when no frame anchor says: above the middle. */
export const DEFAULT_IMPACT: Anchor = [0, -8];

/** The icon (symbol and palette) for an effect type: its kind's, or the default kind's. */
export function effectIcon(manifest: SpriteManifest, type: string) {
  const { kinds, types } = manifest.effects;
  return kinds[types[type] ?? manifest.effects.default]!;
}
