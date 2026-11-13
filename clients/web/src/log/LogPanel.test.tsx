import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { LogPanel } from './LogPanel.tsx';
import type { LogDraw, LogEntry } from './types.ts';

const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
const PEBBLE = 'entity:11111111-0000-4000-8000-0000000000aa';

function entry(seq: number, kind: string, cause: number | null, extra: Partial<LogEntry>): LogEntry {
  return {
    seq,
    area: 'area:a',
    time: 1043 + seq / 100,
    kind,
    cause,
    root: cause === null ? seq : 100,
    resource: null,
    draws: [],
    changes: [],
    ...extra,
  };
}

const scatter: LogDraw = { for: 'scatter', dist: 'gaussian', mean: 0.0, sd: 0.2, value: 0.031 };
const damage: LogDraw = {
  for: 'damage',
  dist: 'gaussian',
  mean: 2.5,
  sd: 1.0,
  truncate: { min: 0.5, max: 4.0 },
  value: 2.5,
};
const brk: LogDraw = { for: 'break', dist: 'bernoulli', p: 0.5, u: 0.12, value: true };

// seq 100 is the root of the throw tree.
const throwTree: LogEntry[] = [
  entry(100, 'action-start', null, {
    root: 100,
    resource: ORBITER,
    action: { type: 'throw', seq: 100 },
    draws: [
      { for: 'behaviour', dist: 'categorical', weights: [2.0, 1.0, 1.0], u: 0.7, value: 2 },
    ],
  }),
  entry(101, 'effect', 100, {
    effect: { type: 'line-attack', params: { source: ORBITER } },
    result: { hit: PILLAR },
    draws: [scatter],
  }),
  entry(102, 'effect', 101, {
    effect: { type: 'create' },
    changes: [{ id: PEBBLE, type: 'entity', op: 'created', parent: 'area:a', state: { name: 'pebble' } }],
  }),
  entry(103, 'action-start', 102, { resource: PEBBLE, action: { type: 'fly', seq: 103 } }),
  entry(104, 'action-end', 103, { resource: PEBBLE, action: { type: 'fly', seq: 103 } }),
  entry(105, 'effect', 104, {
    effect: { type: 'damage', params: { target: PILLAR } },
    result: { hpBefore: 100.0, hpAfter: 97.5 },
    draws: [damage],
  }),
  entry(106, 'effect', 104, { effect: { type: 'break' }, draws: [brk] }),
];

const resource = (id: string, name: string): Resource => ({
  id,
  type: 'entity',
  version: 1,
  children: [],
  state: { name },
});
const resources = new Map<string, Resource>([
  [ORBITER, resource(ORBITER, 'orbiter')],
  [PILLAR, resource(PILLAR, 'pillar')],
]);

describe('LogPanel', () => {
  it('renders a full throw tree with names, nesting and every draw in full', () => {
    const { container } = render(<LogPanel entries={throwTree} resources={resources} />);
    const trees = container.querySelectorAll('.log-tree');
    expect(trees).toHaveLength(1);
    // The tree has effects, so it is expanded.
    expect(screen.getByRole('button', { name: 'Toggle tree 100' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    const start = container.querySelector('[data-seq="100"]') as HTMLElement;
    expect(within(start).getAllByText('orbiter')[0]).toBeInTheDocument();
    expect(start.querySelector('.log-what')).toHaveTextContent('start throw');
    // Nesting: the pebble's fly end is under its start, which is under the create effect.
    expect(container.querySelector('[data-seq="102"] [data-seq="103"] [data-seq="104"]')).not.toBeNull();
    expect(container.querySelector('[data-seq="104"] [data-seq="105"]')).not.toBeNull();
    expect(container.querySelector('[data-seq="104"] [data-seq="106"]')).not.toBeNull();
    // Names from the cache, from the entry's changes (pebble) and the action in words.
    expect(container.querySelector('[data-seq="103"] .log-name')).toHaveTextContent('pebble');
    expect(container.querySelector('[data-seq="105"] .log-name')).toHaveTextContent('pillar');
    expect(container.querySelector('[data-seq="105"] .log-what')).toHaveTextContent(
      'damage -> {"hpBefore":100,"hpAfter":97.5}',
    );
    expect(container.querySelector('[data-seq="101"] .log-what')).toHaveTextContent('line-attack');
    // Every draw in full.
    expect(screen.getByText('behaviour: categorical weights=[2.0, 1.0, 1.0] u=0.7 -> value=2')).toBeInTheDocument();
    expect(screen.getByText('scatter: gaussian mean=0.0 sd=0.2 -> value=0.031')).toBeInTheDocument();
    expect(
      screen.getByText('damage: gaussian mean=2.5 sd=1.0 truncate=[0.5, 4.0) -> value=2.5'),
    ).toBeInTheDocument();
    expect(screen.getByText('break: bernoulli p=0.5 u=0.12 -> value=true')).toBeInTheDocument();
  });

  it('collapses a routine tree to one line and toggles it', () => {
    const entries = [
      entry(1, 'action-start', null, { resource: ORBITER, action: { type: 'orbit-step' } }),
      entry(2, 'action-end', 1, { root: 1, resource: ORBITER, action: { type: 'orbit-step' } }),
    ];
    const { container } = render(<LogPanel entries={entries} resources={resources} />);
    const toggle = screen.getByRole('button', { name: 'Toggle tree 1' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(container.querySelectorAll('.log-entry')).toHaveLength(1);
    expect(container.querySelector('.log-more')).toHaveTextContent('(+1 more)');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(container.querySelectorAll('.log-entry')).toHaveLength(2);
    // An effect tree can be collapsed too.
    const { container: c2 } = render(<LogPanel entries={throwTree} resources={resources} />);
    fireEvent.click(within(c2).getByRole('button', { name: 'Toggle tree 100' }));
    expect(c2.querySelectorAll('.log-entry')).toHaveLength(1);
  });

  it('reports the entity of a hovered or focused entry', () => {
    const onHighlight = vi.fn();
    const { container } = render(
      <LogPanel entries={throwTree} resources={resources} onHighlight={onHighlight} />,
    );
    const damageEntry = container.querySelector('[data-seq="105"]') as HTMLElement;
    fireEvent.mouseOver(damageEntry);
    expect(onHighlight).toHaveBeenLastCalledWith(PILLAR);
    fireEvent.mouseOut(damageEntry);
    expect(onHighlight).toHaveBeenLastCalledWith(null);
    fireEvent.focus(container.querySelector('[data-seq="103"]') as HTMLElement);
    expect(onHighlight).toHaveBeenLastCalledWith(PEBBLE);
  });

  it('shows a short id when nothing names the resource, and the clock and snapshot entries', () => {
    const entries = [
      entry(1, 'snapshot', null, { changes: [{ id: 'area:a', type: 'area', op: 'snapshot' }] }),
      entry(2, 'clock-anchored', null, { reason: 'first-tick', resource: 'area:00000000-0000-4000-8000-0000000000f1' }),
    ];
    const { container } = render(<LogPanel entries={entries} resources={new Map()} />);
    expect(container.querySelector('[data-seq="1"] .log-what')).toHaveTextContent('snapshot (1 resources)');
    expect(container.querySelector('[data-seq="2"] .log-name')).toHaveTextContent('area:…00f1');
    expect(container.querySelector('[data-seq="2"] .log-what')).toHaveTextContent(
      'clock anchored (first-tick)',
    );
  });

  it('says so when the log is empty', () => {
    render(<LogPanel entries={[]} resources={new Map()} />);
    expect(screen.getByText('No log entries yet.')).toBeInTheDocument();
  });
});
