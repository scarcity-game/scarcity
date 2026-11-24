// The sprites of content/sprites, read at build time (Vite imports): the manifest and the SVG
// sheets. Everything else in src/sprites works on these types, so tests can pass their own.
import manifestJson from '../../../../content/sprites/sprites.json';
import humanoid from '../../../../content/sprites/categories/humanoid.svg?raw';
import pillar from '../../../../content/sprites/categories/pillar.svg?raw';
import rodent from '../../../../content/sprites/categories/rodent.svg?raw';
import rubble from '../../../../content/sprites/categories/rubble.svg?raw';
import stairs from '../../../../content/sprites/categories/stairs.svg?raw';
import stash from '../../../../content/sprites/categories/stash.svg?raw';
import stone from '../../../../content/sprites/categories/stone.svg?raw';
import overlays from '../../../../content/sprites/overlays.svg?raw';

export type SpriteState = 'idle' | 'moving' | 'acting' | 'acted-upon' | 'static';

export interface StateFrames {
  frames: string[];
  frameMs: number;
}

export interface SpriteCategory {
  kind: 'static' | 'animated';
  fallbackColor: string;
  states: Partial<Record<SpriteState, StateFrames>>;
}

export interface Palette {
  body: string;
  accent: string;
  detail: string;
  outline: string;
}

export interface SpriteVariant {
  category: string;
  palette: Palette;
  overlays: string[];
}

export interface SpriteManifest {
  grid: number;
  categories: Record<string, SpriteCategory>;
  overlays: Record<string, string>;
  variants: Record<string, SpriteVariant>;
}

export const manifest = manifestJson as unknown as SpriteManifest;

/** The category and overlay sheets as SVG text, inlined once into the page (`SpriteSheets`). */
export const sheetSources: readonly string[] = [
  humanoid,
  pillar,
  rodent,
  rubble,
  stairs,
  stash,
  stone,
  overlays,
];
