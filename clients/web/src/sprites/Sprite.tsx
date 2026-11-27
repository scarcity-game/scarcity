import { useSyncExternalStore, type CSSProperties } from 'react';
import type { Resource } from '../resources/cache.ts';
import { useSpriteClock } from './clock.ts';
import {
  DEFAULT_IMPACT,
  drawnRadius,
  effectIcon,
  frameIndex,
  lookOf,
  overlayPlacement,
  phaseOf,
  spriteScale,
  stateOf,
  type Look,
} from './choice.ts';
import { reportGap } from './gaps.ts';
import {
  manifest,
  sheetSources,
  type Anchor,
  type Overlay,
  type Palette,
  type SpriteManifest,
  type SpriteVariant,
  type StateFrames,
} from './manifest.ts';

/** The sprite sheets, once, hidden: every `<use href="#symbol">` in the page points into them. */
export function SpriteSheets() {
  return (
    <div
      aria-hidden="true"
      className="sprite-sheets"
      // The sheets are build-time content of this repo (checked by content/sprites), not user input.
      dangerouslySetInnerHTML={{ __html: sheetSources.join('') }}
    />
  );
}

/** A palette as the CSS variables the sheets colour themselves with. */
export function paletteStyle(variant: { palette: Palette }): CSSProperties {
  const { body, accent, detail, outline } = variant.palette;
  return {
    '--sprite-body': body,
    '--sprite-accent': accent,
    '--sprite-detail': detail,
    '--sprite-outline': outline,
  } as CSSProperties;
}

const BOX = { x: -16, y: -16, width: 32, height: 32 };

const NO_ANCHORS: Readonly<Record<string, Anchor>> = {};

/**
 * A frame with the variant's overlays at the frame's anchors and, when an effect acts upon the entity
 * (`effect`: its type), the effect's icon at the frame's `impact` anchor, in the effect's own colours.
 */
function Uses({
  frame,
  anchors = NO_ANCHORS,
  variant,
  sprites,
  effect,
}: {
  frame: string;
  anchors?: Readonly<Record<string, Anchor>>;
  variant: SpriteVariant;
  sprites: SpriteManifest;
  effect?: string;
}) {
  return (
    <>
      <use href={`#${frame}`} {...BOX} />
      {variant.overlays.map((name) => (
        <OverlayUse key={name} overlay={sprites.overlays[name]} anchors={anchors} />
      ))}
      {effect !== undefined && <EffectUse type={effect} anchors={anchors} sprites={sprites} />}
    </>
  );
}

function OverlayUse({ overlay, anchors }: { overlay: Overlay | undefined; anchors: Readonly<Record<string, Anchor>> }) {
  const placed = overlay === undefined ? null : overlayPlacement(overlay, anchors);
  if (placed === null) return null;
  return <use href={`#${placed.symbol}`} transform={placed.transform} {...BOX} />;
}

/** An effect's icon, in its own colours, on the frame's `impact` anchor. */
function EffectUse({
  type,
  anchors,
  sprites,
}: {
  type: string;
  anchors: Readonly<Record<string, Anchor>>;
  sprites: SpriteManifest;
}) {
  const icon = effectIcon(sprites, type);
  const [x, y] = anchors.impact ?? DEFAULT_IMPACT;
  return (
    <g className="sprite-effect" transform={`translate(${x} ${y})`} style={paletteStyle(icon)}>
      <use href={`#${icon.symbol}`} {...BOX} />
    </g>
  );
}

const noSubscription = () => () => {};

/** The frame number now showing; the component re-renders only when it changes. */
function useFrameNo(state: StateFrames, id: string): number {
  const clock = useSpriteClock();
  const animated = state.frames.length > 1 && state.frameMs > 0;
  const phase = phaseOf(id, state.frameMs * state.frames.length);
  return useSyncExternalStore(animated ? clock.subscribe : noSubscription, () =>
    animated ? frameIndex(state, clock.now(), phase) : 0,
  );
}

function Disc({ x, y, radius, color }: { x: number; y: number; radius: number; color: string }) {
  return (
    <circle
      className="sprite-disc"
      cx={x}
      cy={y}
      r={radius}
      fill={color}
      stroke="#231d17"
      strokeWidth={1.5}
      vectorEffect="non-scaling-stroke"
    />
  );
}

interface PlayingSpriteProps {
  entity: Resource;
  look: Extract<Look, { kind: 'sprite' }>;
  actedUpon: ReadonlyMap<string, string> | undefined;
  x: number;
  y: number;
}

function PlayingSprite({ entity, look, actedUpon, x, y }: PlayingSpriteProps) {
  const { category, variant } = look;
  const { states } = manifest.categories[category]!;
  const state = stateOf(entity, actedUpon, states);
  const frames = states[state] ?? states.idle!;
  const frameNo = useFrameNo(frames, entity.id);
  const frame = frames.frames[frameNo];
  // The effect's icon shows on anything an effect acts upon, even a pillar with no acted-upon frames.
  const effect = actedUpon?.get(entity.id);
  const scale = spriteScale(entity.radius, manifest.grid);
  return (
    <g
      className="sprite"
      pointerEvents="none"
      data-sprite-state={state}
      transform={`translate(${x} ${y}) scale(${scale})`}
      style={paletteStyle(variant)}
    >
      <Uses
        frame={frame ?? frames.frames[0]!}
        anchors={frames.anchors?.[frameNo]}
        variant={variant}
        sprites={manifest}
        effect={effect}
      />
    </g>
  );
}

interface EntitySpriteProps {
  entity: Resource;
  /** Entities that are the target of an effect in the beat on screen, each to the effect's type. */
  actedUpon?: ReadonlyMap<string, string>;
  /** Where it is drawn: SVG coordinates of its centre (world units, y flipped). */
  x: number;
  y: number;
}

/**
 * What the map draws for an entity: its sprite, scaled so the sprite's circle is the entity's
 * radius (never under `MIN_RADIUS`), or a disc when the prototype has no sprite. A transparent
 * disc under it keeps the whole circle clickable. The sprite itself takes no pointer events: its
 * `<use>`s are rebuilt at every animation frame, and a press on a node that goes before the release
 * is never a click (#393); the hit disc is the one stable target.
 */
export function EntitySprite({ entity, actedUpon, x, y }: EntitySpriteProps) {
  const look = lookOf(entity, manifest, reportGap);
  const radius = drawnRadius(entity.radius);
  return (
    <>
      <circle className="sprite-hit" cx={x} cy={y} r={radius} fill="transparent" />
      {look.kind === 'disc' ? (
        <Disc x={x} y={y} radius={radius} color={look.color} />
      ) : (
        <PlayingSprite entity={entity} look={look} actedUpon={actedUpon} x={x} y={y} />
      )}
    </>
  );
}

/** The entity's picture for the entity panel: the first idle frame, still, `size` px square. */
export function SpriteIcon({ entity, size = 40 }: { entity: Resource; size?: number }) {
  const look = lookOf(entity, manifest, reportGap);
  return (
    <svg
      className="sprite-icon"
      viewBox="-16 -16 32 32"
      width={size}
      height={size}
      aria-hidden="true"
      data-icon={look.kind}
    >
      {look.kind === 'disc' ? (
        <circle r={15} fill={look.color} stroke="#231d17" strokeWidth={1.5} />
      ) : (
        <IconUses look={look} />
      )}
    </svg>
  );
}

function IconUses({ look }: { look: Extract<Look, { kind: 'sprite' }> }) {
  const { states } = manifest.categories[look.category]!;
  const frames = states.idle ?? states.static!;
  return (
    <g style={paletteStyle(look.variant)}>
      <Uses frame={frames.frames[0]!} anchors={frames.anchors?.[0]} variant={look.variant} sprites={manifest} />
    </g>
  );
}
