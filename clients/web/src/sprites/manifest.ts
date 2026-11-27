// The sprites of content/sprites, read at build time (Vite imports): the manifest and the SVG
// sheets. Everything else in src/sprites works on these types, so tests can pass their own.
import manifestJson from '../../../../content/sprites/sprites.json';
import effects from '../../../../content/sprites/effects.svg?raw';
import overlays from '../../../../content/sprites/overlays.svg?raw';

export type SpriteState = 'idle' | 'moving' | 'acting' | 'acted-upon' | 'static';

/** A named point of a frame on the 32-unit grid: [x, y] or [x, y, degrees clockwise from pointing right]. */
export type Anchor = [number, number] | [number, number, number];

export interface StateFrames {
  frames: string[];
  frameMs: number;
  /** One per frame: its named points (`impact`, `head`, `hand.main`, ...), where overlays and effects go. */
  anchors?: Record<string, Anchor>[];
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

/** An overlay drawn where it is, or one that hangs on an anchor: moved (and turned) from `at` to the frame's anchor. */
export type Overlay = string | { symbol: string; anchor: string; at: Anchor };

/** The icons drawn on an entity an effect acts upon, by the effect's kind. */
export interface Effects {
  kinds: Record<string, { symbol: string; palette: Palette }>;
  /** An effect type (`loggedEffect.type`) to its kind; any other type is `default`. */
  types: Record<string, string>;
  default: string;
}

export interface SpriteVariant {
  category: string;
  palette: Palette;
  overlays: string[];
}

/** A wall pattern: one tileable symbol `pattern.<name>`, repeated every `metres` in world space. */
export interface WallPattern {
  metres: number;
  fallbackColor: string;
}

export interface SpriteManifest {
  grid: number;
  patterns: Record<string, WallPattern>;
  /** A wall `material` to the pattern it is drawn with when its game maps it to none. */
  materials: Record<string, string>;
  categories: Record<string, SpriteCategory>;
  overlays: Record<string, Overlay>;
  effects: Effects;
  variants: Record<string, SpriteVariant>;
}

export const manifest = manifestJson as unknown as SpriteManifest;

// Every category and pattern sheet of content/sprites, by build-time glob: a new sheet needs no change here.
const categorySheets = import.meta.glob<string>('../../../../content/sprites/categories/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true,
});
const patternSheets = import.meta.glob<string>('../../../../content/sprites/patterns/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** The category, pattern and overlay sheets as SVG text, inlined once into the page (`SpriteSheets`). */
export const sheetSources: readonly string[] = [
  ...Object.values(categorySheets),
  ...Object.values(patternSheets),
  overlays,
  effects,
];
