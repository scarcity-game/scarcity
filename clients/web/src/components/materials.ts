import { manifest, type SpriteManifest } from '../sprites/manifest.ts';

export const NEUTRAL_FILL = '#8a8a8a';

/** The game's map from a wall material to its pattern name (`Game.materialPatterns`). */
export type MaterialPatterns = Readonly<Record<string, string>>;

/** How a wall is filled: a pattern of the manifest, or a flat colour when there is none. */
export interface MaterialLook {
  pattern?: string;
  color: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function own(record: unknown, key: string): unknown {
  return isRecord(record) && Object.hasOwn(record, key) ? record[key] : undefined;
}

function patternNamed(sprites: SpriteManifest, name: unknown): string | undefined {
  return typeof name === 'string' && Object.hasOwn(sprites.patterns, name) ? name : undefined;
}

/**
 * The look of a material: the game's pattern for it, else the manifest's default, else a neutral
 * grey. Data lookups only; nothing here knows a material's name.
 */
export function lookOf(
  material: unknown,
  materialPatterns?: unknown,
  sprites: SpriteManifest = manifest,
): MaterialLook {
  if (typeof material !== 'string') return { color: NEUTRAL_FILL };
  const pattern =
    patternNamed(sprites, own(materialPatterns, material)) ??
    patternNamed(sprites, own(sprites.materials, material));
  return pattern
    ? { pattern, color: sprites.patterns[pattern]!.fallbackColor }
    : { color: NEUTRAL_FILL };
}

/** The id of the SVG `<pattern>` for a pattern name inside one room (`scope` keeps ids unique per page). */
export function patternId(scope: string, name: string): string {
  return `${scope.replace(/[^A-Za-z0-9_-]/g, '')}-${name}`;
}

/** The `fill` of a wall: `url(#pattern)` when it has a pattern, else the flat colour. */
export function fillOf(look: MaterialLook, scope: string): string {
  return look.pattern ? `url(#${patternId(scope, look.pattern)})` : look.color;
}
