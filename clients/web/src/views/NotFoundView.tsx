import { Link, useLocation } from 'react-router';

export function NotFoundView() {
  const { pathname } = useLocation();
  return (
    <>
      <h1>Not found</h1>
      <p>
        There is nothing at <code>{pathname}</code>. <Link to="/">Back to the start</Link>.
      </p>
    </>
  );
}
