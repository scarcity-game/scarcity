import { useSyncExternalStore, type CSSProperties } from 'react';
import type { Resource } from '../resources/cache.ts';
import { useSpriteClock } from './clock.ts';
import {
  drawnRadius,
  frameIndex,
  lookOf,
  phaseOf,
  spriteScale,
  stateOf,
  type Look,
} from './choice.ts';
import { reportGap } from './gaps.ts';
import {
  manifest,
  sheetSources,
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

/** The variant's palette as the CSS variables the sheets colour themselves with. */
export function paletteStyle(variant: SpriteVariant): CSSProperties {
  const { body, accent, detail, outline } = variant.palette;
  return {
    '--sprite-body': body,
    '--sprite-accent': accent,
    '--sprite-detail': detail,
    '--sprite-outline': outline,
  } as CSSProperties;
}

const BOX = { x: -16, y: -16, width: 32, height: 32 };

function Uses({
  frame,
  variant,
  sprites,
}: {
  frame: string;
  variant: SpriteVariant;
  sprites: SpriteManifest;
}) {
  return (
    <>
      <use href={`#${frame}`} {...BOX} />
      {variant.overlays.map((name) => (
        <use key={name} href={`#${sprites.overlays[name]}`} {...BOX} />
      ))}
    </>
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
  actedUpon: ReadonlySet<string> | undefined;
  x: number;
  y: number;
}

function PlayingSprite({ entity, look, actedUpon, x, y }: PlayingSpriteProps) {
  const { category, variant } = look;
  const { states } = manifest.categories[category]!;
  const state = stateOf(entity, actedUpon, states);
  const frames = states[state] ?? states.idle!;
  const frame = frames.frames[useFrameNo(frames, entity.id)];
  const scale = spriteScale(entity.radius, manifest.grid);
  return (
    <g
      className="sprite"
      pointerEvents="none"
      data-sprite-state={state}
      transform={`translate(${x} ${y}) scale(${scale})`}
      style={paletteStyle(variant)}
    >
      <Uses frame={frame ?? frames.frames[0]!} variant={variant} sprites={manifest} />
    </g>
  );
}

interface EntitySpriteProps {
  entity: Resource;
  /** Entities that are the target of an effect in the beat on screen. */
  actedUpon?: ReadonlySet<string>;
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
      <Uses frame={frames.frames[0]!} variant={look.variant} sprites={manifest} />
    </g>
  );
}
