import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderAt } from './test/renderAt.tsx';

const HELLO = 'Hello, world. The dungeon is being dug.';

describe('routing', () => {
  it('navigates back home from the not-found view without a reload', () => {
    renderAt('/nowhere');
    expect(screen.queryByText(HELLO)).toBeNull();

    fireEvent.click(screen.getByRole('link', { name: 'Back to the start' }));

    expect(screen.getByText(HELLO)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Not found' })).toBeNull();
  });

  it('navigates home via the header brand link, keeping the shell', () => {
    renderAt('/deep/link/somewhere');

    const header = screen.getByRole('banner');
    fireEvent.click(within(header).getByRole('link', { name: 'Scarcity' }));

    expect(screen.getByRole('banner')).toBe(header);
    expect(within(screen.getByRole('main')).getByText(HELLO)).toBeInTheDocument();
  });

  it('shows the not-found view for a route that does not exist yet', () => {
    renderAt('/inventory');
    expect(screen.getByRole('heading', { name: 'Not found' })).toBeInTheDocument();
  });

  it('serves the login and register views as routes of the shell', () => {
    renderAt('/login');
    expect(screen.getByRole('heading', { level: 1, name: 'Log in' })).toBeInTheDocument();
    cleanup();
    renderAt('/register');
    expect(screen.getByRole('heading', { level: 1, name: 'Register' })).toBeInTheDocument();
  });

  it.each(['/', '/login', '/register', '/nowhere'])('renders exactly one h1 at %s', (path) => {
    renderAt(path);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('renders unicode paths in the not-found view as text', () => {
    renderAt('/地下/🐉');
    expect(within(screen.getByRole('main')).getByText('/地下/🐉')).toBeInTheDocument();
  });
});
