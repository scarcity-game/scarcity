import { AuthProvider } from './auth/AuthProvider.tsx';
import { AppRoutes } from './routes.tsx';

// Everything below the router: shared by main.tsx and the tests' renderAt.
export function App() {
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
