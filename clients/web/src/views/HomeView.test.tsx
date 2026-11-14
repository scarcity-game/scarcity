import { screen, within } from '@testing-library/react';
import { ALICE, json, mockApi, storeTestSession } from '../test/mockApi.ts';
import { describe, expect, it } from 'vitest';
import { renderAt } from '../test/renderAt.tsx';

describe('home view', () => {
  it('renders the Scarcity heading and the hello-world line', () => {
    renderAt('/');

    expect(screen.getByRole('heading', { level: 1, name: 'Scarcity' })).toBeInTheDocument();
    expect(screen.getByText('Hello, world. The dungeon is being dug.')).toBeInTheDocument();
  });

  it('prompts a logged-out visitor to log in or register', () => {
    renderAt('/');
    const main = within(screen.getByRole('main'));
    expect(main.getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login');
    expect(main.getByRole('link', { name: 'register' })).toHaveAttribute('href', '/register');
  });

  it('greets the logged-in player by name with status role and label', async () => {
    storeTestSession();
    mockApi({ 'GET /api/auth/me': () => json(200, ALICE) });
    renderAt('/');

    // Check that the welcome message is accessible with status role and label
    const welcomeElement = await screen.findByRole('status', { name: 'Signed in' });
    expect(welcomeElement).toBeInTheDocument();
    expect(welcomeElement).toHaveTextContent(/Welcome, alice\./);
    expect(within(welcomeElement).getByRole('link', { name: 'Games' })).toHaveAttribute(
      'href',
      '/games',
    );
  });
});
