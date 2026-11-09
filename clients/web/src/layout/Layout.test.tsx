import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderAt } from '../test/renderAt.tsx';

describe('layout shell', () => {
  it('renders a header with the brand and the route inside main', () => {
    renderAt('/');

    const header = screen.getByRole('banner');
    expect(within(header).getByRole('link', { name: 'Scarcity' })).toHaveAttribute('href', '/');

    const main = screen.getByRole('main');
    expect(within(main).getByRole('heading', { name: 'Scarcity' })).toBeInTheDocument();
  });

  it('shows log in and register links in the header when logged out', () => {
    renderAt('/');

    const nav = within(screen.getByRole('banner')).getByRole('navigation', { name: 'Account' });
    expect(within(nav).getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
    expect(within(nav).getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register');
    expect(within(nav).queryByRole('button', { name: 'Log out' })).toBeNull();
  });

  it('renders a not-found view inside the shell for unknown routes', () => {
    renderAt('/some/unknown/route');

    expect(screen.getByRole('banner')).toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(within(main).getByRole('heading', { name: 'Not found' })).toBeInTheDocument();
    expect(within(main).getByText('/some/unknown/route')).toBeInTheDocument();
    expect(within(main).getByRole('link', { name: 'Back to the start' })).toHaveAttribute(
      'href',
      '/',
    );
    expect(within(main).queryByText('Hello, world. The dungeon is being dug.')).toBeNull();
  });
});
