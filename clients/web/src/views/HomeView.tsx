import { Link } from 'react-router';
import { useAuth } from '../auth/AuthProvider.tsx';
import { SessionCheck } from '../auth/SessionCheck.tsx';

export function HomeView() {
  const { status, user } = useAuth();
  return (
    <>
      <h1>Scarcity</h1>
      <p>Hello, world. The dungeon is being dug.</p>
      {status === 'checking' && <SessionCheck />}
      {status === 'authenticated' && user && (
        <p>
          Welcome, {user.username}. <Link to="/games">Games</Link>.
        </p>
      )}
      {status === 'anonymous' && (
        <p>
          <Link to="/login">Log in</Link> or <Link to="/register">register</Link> to play.
        </p>
      )}
    </>
  );
}
