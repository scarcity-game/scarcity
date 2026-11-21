import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Resource } from '../resources/cache.ts';
import { Inspector } from './Inspector.tsx';

const area: Resource = {
  id: 'area:1',
  resourceKind: 'area',
  version: 1,
  children: ['entity:2'],
  name: 'Level 3',
  depth: 3,
  width: 10,
  height: 8,
  time: 0,
};
const stairs: Resource = {
  id: 'entity:2',
  resourceKind: 'entity',
  version: 1,
  children: [],
  entityKind: 'fixed',
  prototype: 'stairs-up',
  name: 'stairs up',
  to: null,
};

describe('Inspector with stairs selected', () => {
  it('names the parent without suggesting travel, and selects it on click', () => {
    const onSelect = vi.fn();
    render(
      <Inspector
        selected={stairs}
        area={area}
        resources={
          new Map([
            [area.id, area],
            [stairs.id, stairs],
          ])
        }
        onSelect={onSelect}
        isStale={() => false}
      />,
    );
    const panel = screen.getByRole('complementary', { name: 'Inspector' });
    expect(within(panel).queryByRole('button', { name: /^Up to/ })).toBeNull();
    expect(within(panel).getByText('up (to another level)')).toBeInTheDocument();
    expect(within(panel).getByText('not made yet')).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'Show area: Level 3' }));
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});
