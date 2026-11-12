import { useRoutes, type RouteObject } from 'react-router';
import { RequireAuth } from './auth/RequireAuth.tsx';
import { Layout } from './layout/Layout.tsx';
import { AreaView } from './views/AreaView.tsx';
import { GameView } from './views/GameView.tsx';
import { GamesView } from './views/GamesView.tsx';
import { HomeView } from './views/HomeView.tsx';
import { LoginView } from './views/LoginView.tsx';
import { NotFoundView } from './views/NotFoundView.tsx';
import { PlayView } from './views/PlayView.tsx';
import { RegisterView } from './views/RegisterView.tsx';

// The routes table. New screens are added as children of the layout route so
// they render inside the shared <header> + <main> shell. Screens that need a
// logged-in player go under the <RequireAuth /> route.
export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { index: true, element: <HomeView /> },
      { path: 'login', element: <LoginView /> },
      { path: 'register', element: <RegisterView /> },
      {
        element: <RequireAuth />,
        children: [
          { path: 'play', element: <PlayView /> },
          { path: 'games', element: <GamesView /> },
          { path: 'games/:gameId', element: <GameView /> },
          { path: 'games/:gameId/areas/:areaId', element: <AreaView /> },
        ],
      },
      { path: '*', element: <NotFoundView /> },
    ],
  },
];

export function AppRoutes() {
  return useRoutes(routes);
}
