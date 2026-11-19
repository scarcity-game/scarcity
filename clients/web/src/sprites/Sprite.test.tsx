import { act, render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { SpriteClock, SpriteClockContext } from './clock.ts';
import { reportGap, resetReportedGaps } from './gaps.ts';
import { manifest } from './manifest.ts';
import { EntitySprite, SpriteIcon, SpriteSheets } from './Sprite.tsx';

const entity = (fields: Record<string, unknown>): Resource => ({
  id: 'entity:1',
  resourceKind: 'entity',
  version: 1,
  children: [],
  radius: 0.5,
  ...fields,
});

const inSvg = (node: ReactNode) => render(<svg>{node}</svg>);
const firstHref = (container: Element) => container.querySelector('use')?.getAttribute('href');

describe('EntitySprite', () => {
  it('draws one use of the frame, with the palette, scaled to the radius', () => {
    const e = entity({ prototype: 'pillar' });
    const { container } = inSvg(<EntitySprite entity={e} x={3} y={4} />);
    const g = container.querySelector<SVGGElement>('g.sprite')!;
    expect(g.getAttribute('transform')).toBe(`translate(3 4) scale(${0.5 / 16})`);
    expect(g.getAttribute('data-sprite-state')).toBe('static');
    expect(g.querySelectorAll('use')).toHaveLength(1);
    expect(firstHref(g)).toBe('#pillar.static.0');
    expect(g.style.getPropertyValue('--sprite-body')).toBe(manifest.variants.pillar!.palette.body);
  });

  it('adds the variant overlays as further uses', () => {
    const { container } = inSvg(
      <EntitySprite entity={entity({ prototype: 'orbiter' })} x={0} y={0} />,
    );
    const hrefs = [...container.querySelectorAll('use')].map((u) => u.getAttribute('href'));
    expect(hrefs).toEqual(['#humanoid.idle.0', '#overlay.hood']);
  });

  it('shows the state of the action and of the log', () => {
    const step = entity({ prototype: 'mouse', actionInProgress: { type: 'step' } });
    const moving = inSvg(<EntitySprite entity={step} x={0} y={0} />);
    expect(firstHref(moving.container)).toMatch(/^#rodent\.moving\./);
    const hit = inSvg(<EntitySprite entity={step} actedUpon={new Set(['entity:1'])} x={0} y={0} />);
    expect(firstHref(hit.container)).toMatch(/^#rodent\.acted-upon\./);
  });

  it('draws a pebble at the minimum size', () => {
    const e = entity({ prototype: 'pebble', radius: 0.015 });
    const { container } = inSvg(<EntitySprite entity={e} x={0} y={0} />);
    const transform = container.querySelector('g.sprite')?.getAttribute('transform');
    expect(transform).toBe(`translate(0 0) scale(${0.15 / 16})`);
  });
});

describe('EntitySprite: fallback', () => {
  beforeEach(() => resetReportedGaps());
  afterEach(() => vi.restoreAllMocks());

  it('draws a disc in the category colour, and logs the gap once however often it draws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const e = entity({ prototype: 'humanoid' });
    const first = inSvg(<EntitySprite entity={e} x={0} y={0} />);
    inSvg(<EntitySprite entity={e} x={1} y={1} />);
    first.rerender(
      <svg>
        <EntitySprite entity={e} x={2} y={2} />
      </svg>,
    );
    const disc = first.container.querySelector('circle.sprite-disc');
    expect(disc?.getAttribute('fill')).toBe(manifest.categories.humanoid!.fallbackColor);
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it('logs each distinct gap, and the same one only once', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    reportGap('a');
    reportGap('a');
    reportGap('b');
    expect(warn).toHaveBeenCalledTimes(2);
  });
});

describe('animated frames', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('steps through the frames as the clock runs', () => {
    let wall = 0;
    const clock = new SpriteClock(() => wall);
    clock.configure({ running: true, rate: 1 });
    const e = entity({ prototype: 'mouse', actionInProgress: { type: 'step' } });
    const { container } = render(
      <SpriteClockContext.Provider value={clock}>
        <svg>
          <EntitySprite entity={e} x={0} y={0} />
        </svg>
      </SpriteClockContext.Provider>,
    );
    const seen = new Set<string | null | undefined>();
    for (let i = 0; i < 20; i += 1) {
      act(() => {
        wall += 60;
        vi.advanceTimersByTime(60);
      });
      seen.add(firstHref(container));
    }
    expect(seen.size).toBe(manifest.categories.rodent!.states.moving!.frames.length);
  });
});

describe('SpriteIcon and SpriteSheets', () => {
  it('shows the idle frame in the palette, or a disc', () => {
    const icon = render(<SpriteIcon entity={entity({ prototype: 'mouse' })} />);
    expect(firstHref(icon.container)).toBe('#rodent.idle.0');
    const gap = render(<SpriteIcon entity={entity({ prototype: 'dragon' })} />);
    expect(gap.container.querySelector('svg')?.getAttribute('data-icon')).toBe('disc');
  });

  it('inlines every symbol the manifest names', () => {
    const { container } = render(<SpriteSheets />);
    const ids = new Set([...container.querySelectorAll('symbol')].map((s) => s.id));
    for (const category of Object.values(manifest.categories)) {
      for (const state of Object.values(category.states)) {
        for (const frame of state.frames) expect(ids.has(frame)).toBe(true);
      }
    }
    for (const symbol of Object.values(manifest.overlays)) expect(ids.has(symbol)).toBe(true);
  });
});
