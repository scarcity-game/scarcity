import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { LogPanel } from './LogPanel.tsx';
import type { LogDraw, LogEntry } from './types.ts';

const ORBITER = 'entity:00000000-0000-4000-8000-000000000004';
const PILLAR = 'entity:00000000-0000-4000-8000-000000000003';
const PEBBLE = 'entity:11111111-0000-4000-8000-0000000000aa';

function entry(
  seq: number,
  kind: string,
  cause: number | null,
  extra: Partial<LogEntry>,
): LogEntry {
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
    actionInProgress: { type: 'throw', seq: 100 },
    draws: [{ for: 'behaviour', dist: 'categorical', weights: [2.0, 1.0, 1.0], u: 0.7, value: 2 }],
  }),
  entry(101, 'effect', 100, {
    loggedEffect: { type: 'line-attack', params: { source: ORBITER } },
    result: { hit: PILLAR },
    draws: [scatter],
  }),
  entry(102, 'effect', 101, {
    loggedEffect: { type: 'create' },
    changes: [
      { id: PEBBLE, type: 'entity', op: 'created', parent: 'area:a', state: { name: 'pebble' } },
    ],
  }),
  entry(103, 'action-start', 102, {
    resource: PEBBLE,
    actionInProgress: { type: 'fly', seq: 103 },
  }),
  entry(104, 'action-end', 103, { resource: PEBBLE, actionInProgress: { type: 'fly', seq: 103 } }),
  entry(105, 'effect', 104, {
    loggedEffect: { type: 'damage', params: { target: PILLAR } },
    result: { hpBefore: 100.0, hpAfter: 97.5 },
    draws: [damage],
  }),
  entry(106, 'effect', 104, { loggedEffect: { type: 'break' }, draws: [brk] }),
];

const resource = (id: string, name: string): Resource => ({
  id,
  resourceKind: 'entity',
  version: 1,
  children: [],
  name,
});
const resources = new Map<string, Resource>([
  [ORBITER, resource(ORBITER, 'orbiter')],
  [PILLAR, resource(PILLAR, 'pillar')],
]);

const interruptions: LogEntry[] = [
  entry(200, 'effect', null, {
    loggedEffect: { type: 'damage', params: { target: ORBITER } },
    actedUpon: [
      { resource: ORBITER, interrupted: true },
      { resource: PILLAR, interrupted: false },
    ],
  }),
  entry(201, 'action-interrupted', 200, {
    resource: ORBITER,
    actionInProgress: { type: 'step' },
    result: { reached: false, stoppedAt: { x: 3, y: 2 } },
  }),
  entry(202, 'contact', null, { resource: ORBITER, result: { resources: [ORBITER, PILLAR] } }),
  entry(203, 'action-interrupted', 202, { resource: ORBITER, actionInProgress: { type: 'step' } }),
  entry(204, 'contact', null, { resource: ORBITER, result: { resources: [ORBITER, 'boundary'] } }),
  entry(205, 'action-interrupted', 204, { resource: ORBITER, actionInProgress: { type: 'step' } }),
];

describe('LogPanel interruptions', () => {
  it('shows the cause of an interruption and who was acted upon', () => {
    const { container } = render(<LogPanel entries={interruptions} resources={resources} />);
    const text = (seq: number) => container.querySelector(`[data-seq="${seq}"]`)!.textContent;
    expect(text(201)).toContain('interrupted step at (3, 2) m by the damage effect');
    expect(text(203)).toContain('by contact with pillar');
    expect(text(205)).toContain("by contact with the area's boundary");
    expect(text(200)).toContain('acted upon: orbiter, action interrupted');
    expect(text(200)).toContain('acted upon: pillar, in recovery, not interrupted');
  });
});

describe('LogPanel', () => {
  it('renders every entry as its own row, in log order, with no nesting', () => {
    const { container } = render(<LogPanel entries={[...throwTree].reverse()} resources={resources} />);
    const rows = container.querySelectorAll('.log-rows > .log-entry');
    expect([...rows].map((r) => r.getAttribute('data-seq'))).toEqual(
      ['100', '101', '102', '103', '104', '105', '106'],
    );
    expect(container.querySelector('.log-entry .log-entry')).toBeNull();
    const start = container.querySelector('[data-seq="100"]') as HTMLElement;
    expect(within(start).getAllByText('orbiter')[0]).toBeInTheDocument();
    expect(start.querySelector('.log-what')).toHaveTextContent('start throw');
  });

  it('shows names from the cache and the changes, and every draw in full', () => {
    const { container } = render(<LogPanel entries={throwTree} resources={resources} />);
    // Names from the cache, from the entry's changes (pebble) and the action in words.
    expect(container.querySelector('[data-seq="103"] .log-name')).toHaveTextContent('pebble');
    expect(container.querySelector('[data-seq="105"] .log-name')).toHaveTextContent('pillar');
    expect(container.querySelector('[data-seq="105"] .log-what')).toHaveTextContent(
      'damage -> {"hpBefore":100,"hpAfter":97.5}',
    );
    expect(container.querySelector('[data-seq="101"] .log-what')).toHaveTextContent('line-attack');
    // Every draw in full.
    expect(
      screen.getByText('behaviour: categorical weights=[2, 1, 1] u=0.7 -> value=2'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('scatter: gaussian mean=0 sd=0.2 -> value=0.031'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('damage: gaussian mean=2.5 sd=1 truncate=[0.5, 4) -> value=2.5'),
    ).toBeInTheDocument();
    expect(screen.getByText('break: bernoulli p=0.5 u=0.12 -> value=true')).toBeInTheDocument();
  });

  it('links an entry to a cause from an earlier time, and selects it on a click', () => {
    const entries = [
      entry(1, 'action-start', null, { time: 1042, resource: PEBBLE, actionInProgress: { type: 'decay', seq: 1 } }),
      entry(2, 'action-end', 1, { time: 1062, resource: PEBBLE, actionInProgress: { type: 'decay', seq: 1 } }),
      entry(3, 'effect', 2, { time: 1062, loggedEffect: { type: 'destroy' } }),
    ];
    const onSelect = vi.fn();
    const { container } = render(<LogPanel entries={entries} resources={resources} onSelect={onSelect} />);
    const link = container.querySelector('[data-seq="2"] .log-cause') as HTMLElement;
    expect(link).toHaveTextContent(/^← from ~17.37 min start decay$/);
    fireEvent.click(link);
    expect(onSelect).toHaveBeenCalledWith(1);
    // Its cause is at the same time, just above: no link.
    expect(container.querySelector('[data-seq="3"] .log-cause')).toBeNull();
    // And none without a cause.
    expect(container.querySelector('[data-seq="1"] .log-cause')).toBeNull();
  });

  it('shows the cause as text when entries cannot be selected', () => {
    const entries = [
      entry(1, 'action-start', null, { time: 1042, resource: PEBBLE, actionInProgress: { type: 'decay', seq: 1 } }),
      entry(2, 'action-end', 1, { time: 1062, resource: PEBBLE, actionInProgress: { type: 'decay', seq: 1 } }),
    ];
    const { container } = render(<LogPanel entries={entries} resources={resources} />);
    expect(container.querySelector('[data-seq="2"] .log-cause')?.tagName).toBe('SPAN');
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

  it("selects an entry's entity when its name is clicked, without selecting the entry", () => {
    const onSelectEntity = vi.fn();
    const onSelect = vi.fn();
    const { container } = render(
      <LogPanel
        entries={throwTree}
        resources={resources}
        onSelect={onSelect}
        onSelectEntity={onSelectEntity}
      />,
    );
    const damageEntry = container.querySelector('[data-seq="105"]') as HTMLElement;
    fireEvent.click(within(damageEntry).getAllByRole('button')[0]!);
    expect(onSelectEntity).toHaveBeenCalledWith(PILLAR);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('shows a short id when nothing names the resource, and the snapshot entry', () => {
    const entries = [
      entry(1, 'snapshot', null, { changes: [{ id: 'area:a', type: 'area', op: 'snapshot' }] }),
      entry(2, 'action-start', null, {
        resource: 'entity:00000000-0000-4000-8000-0000000000f1',
        actionInProgress: { type: 'wait', seq: 2 },
      }),
    ];
    const { container } = render(<LogPanel entries={entries} resources={new Map()} />);
    expect(container.querySelector('[data-seq="1"] .log-what')).toHaveTextContent(
      'snapshot (1 resources)',
    );
    expect(container.querySelector('[data-seq="2"] .log-name')).toHaveTextContent('entity:…00f1');
  });

  describe('intent entries', () => {
    const intent = (extra: Partial<LogEntry>) =>
      entry(1, 'intent', null, {
        resource: ORBITER,
        controller: { kind: 'service', principal: 'orbital' },
        actionSpec: { type: 'throw', params: {} },
        ...extra,
      });

    it('shows who chose what, and the reported draw in full, marked as reported', () => {
      const reported = {
        draw: { dist: 'categorical', weights: [2.0, 1.0, 1.0], u: 0.61, value: 2 },
      };
      const { container } = render(
        <LogPanel entries={[intent({ reported })]} resources={resources} />,
      );
      const line = container.querySelector('[data-seq="1"]')!;
      expect(line.querySelector('.log-name')).toHaveTextContent('orbiter');
      expect(line.querySelector('.log-what')).toHaveTextContent(
        'orbital chose throw (reported: p = 0.25, u = 0.61)',
      );
      expect(line.querySelector('.log-draw-reported')).toHaveTextContent(
        'reported: categorical weights=[2, 1, 1] u=0.61 -> value=2',
      );
    });

    it('shows an intent without a reported draw plainly, with no draw list', () => {
      const { container } = render(<LogPanel entries={[intent({})]} resources={resources} />);
      const line = container.querySelector('[data-seq="1"]')!;
      expect(line.querySelector('.log-what')).toHaveTextContent('orbital chose throw');
      expect(line.querySelector('.log-what')).not.toHaveTextContent('reported');
      expect(line.querySelector('.log-draws')).toBeNull();
    });

    it('shows a wait as its intent, start and end, three rows, nothing folded', () => {
      const entries = [
        intent({ seq: 1, actionSpec: { type: 'wait', params: {} } }),
        entry(2, 'action-start', 1, {
          root: 1,
          resource: ORBITER,
          actionInProgress: { type: 'wait', seq: 2 },
        }),
        entry(3, 'action-end', 2, {
          root: 1,
          resource: ORBITER,
          actionInProgress: { type: 'wait', seq: 2 },
        }),
      ];
      const { container } = render(<LogPanel entries={entries} resources={resources} />);
      expect(container.querySelectorAll('.log-entry')).toHaveLength(3);
    });
  });

  it('says so when the log is empty', () => {
    render(<LogPanel entries={[]} resources={new Map()} />);
    expect(screen.getByText('No log entries yet.')).toBeInTheDocument();
  });
});

describe('LogPanel numbers', () => {
  it('cuts numbers to 4 figures and keeps the exact ones in tooltips', () => {
    const draw = { for: 'scatter', dist: 'gaussian', mean: 4.2, sd: 1.123456789, value: 3.714159265 };
    const entry = { ...throwTree[0]!, time: 2149, draws: [draw] };
    render(<LogPanel entries={[entry]} resources={resources} />);
    const line = screen.getByText('scatter: gaussian mean=4.2 sd=1.123 -> value=3.714');
    expect(line).toHaveAttribute('title', JSON.stringify(draw));
    expect(screen.getByText('~35.82 min')).toHaveAttribute('title', '2149 s');
  });
});
