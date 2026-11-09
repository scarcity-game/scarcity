import { startTransition } from 'react';
import { Link, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthProvider.tsx';

// App shell: a header with the brand and the session controls, and a <main>
// that each route renders into.
export function Layout() {
  return (
    <>
      <header className="app-header">
        <Link to="/" className="brand">
          Scarcity
        </Link>
        <SessionControls />
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </>
  );
}

function SessionControls() {
  const { status, user, logout } = useAuth();
  const navigate = useNavigate();

  return (
    <nav className="session-controls" aria-label="Account">
      {status === 'checking' && <span className="session-check">Checking session…</span>}
      {status === 'anonymous' && (
        <>
          <Link to="/login">Log in</Link>
          <Link to="/register">Register</Link>
        </>
      )}
      {status === 'authenticated' && user && (
        <>
          <span className="session-user">{user.username}</span>
          <button
            type="button"
            onClick={() => {
              // Router navigations are transitions; ending the session in the
              // same transition means a protected view never renders logged
              // out (which would redirect to /login?next=...) before home does.
              startTransition(() => {
                logout();
                navigate('/');
              });
            }}
          >
            Log out
          </button>
        </>
      )}
    </nav>
  );
}
