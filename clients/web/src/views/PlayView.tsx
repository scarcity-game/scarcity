import { useAuth } from '../auth/AuthProvider.tsx';

// Placeholder for the game views. Rendered behind <RequireAuth>.
export function PlayView() {
  const { user } = useAuth();
  return (
    <>
      <h1>Play</h1>
      <p>The dungeon is not ready for you yet, {user?.username}.</p>
    </>
  );
}
