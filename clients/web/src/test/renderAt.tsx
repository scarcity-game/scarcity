import { render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { App } from '../App.tsx';

// Renders the full app (auth provider + routes + layout) at the given path.
export function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  );
}

// A hidden element holding the router location, so tests can assert redirects.
function LocationProbe() {
  const { pathname, search } = useLocation();
  return (
    <output data-testid="location" hidden>
      {pathname + search}
    </output>
  );
}

/** The current in-app location (path + query) of the app rendered by renderAt. */
export function currentLocation(): string {
  return screen.getByTestId('location').textContent ?? '';
}
