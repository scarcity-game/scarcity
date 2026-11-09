import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './AuthProvider.tsx';
import { withNext } from './returnTo.ts';
import { SessionCheck } from './SessionCheck.tsx';

/**
 * Protected-route wrapper. Use as a layout route element (renders <Outlet/>)
 * or around children. Logged out → /login?next=<current path>.
 */
export function RequireAuth({ children }: { children?: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'checking') return <SessionCheck />;
  if (status === 'anonymous') {
    const here = `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to={withNext('/login', here)} replace />;
  }
  return children ?? <Outlet />;
}
