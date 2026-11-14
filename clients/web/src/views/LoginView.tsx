import { useRef, useState, type FormEvent } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { errorMessage } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import { safeReturnTo, withNext } from '../auth/returnTo.ts';
import { SessionCheck } from '../auth/SessionCheck.tsx';
import { Field, FormError } from '../forms/Field.tsx';

interface Errors {
  username?: string;
  password?: string;
}

export const INVALID_CREDENTIALS = 'Invalid username or password';

export function LoginView() {
  const { status, endReason, login } = useAuth();
  const [params] = useSearchParams();
  const next = safeReturnTo(params.get('next'));

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const usernameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  // Logged in (including right after a successful submit): go to the target.
  if (status === 'authenticated') return <Navigate to={next} replace />;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const name = username.trim();
    // Only presence is checked here: any wrong credentials get the server's 401.
    const found: Errors = {
      username: name === '' ? 'Enter your username.' : undefined,
      password: password === '' ? 'Enter your password.' : undefined,
    };
    setErrors(found);
    setFormError(null);
    if (found.username || found.password) {
      (found.username ? usernameRef : passwordRef).current?.focus();
      return;
    }

    setPending(true);
    try {
      await login({ username: name, password });
      // The status change above redirects.
    } catch (err) {
      setFormError(errorMessage(err, { unauthorized: INVALID_CREDENTIALS }));
      setPending(false);
    }
  }

  return (
    <>
      <h1>Log in</h1>
      {status === 'checking' ? (
        <SessionCheck />
      ) : (
        <form className="auth-form" onSubmit={onSubmit} noValidate>
          {(endReason === 'expired' || endReason === 'unauthorized') && (
            <p role="status">Your session has ended. Please log in again.</p>
          )}
          <FormError message={formError} />
          <Field
            id="username"
            label="Username"
            type="text"
            value={username}
            onChange={(v) => {
              setUsername(v);
              setErrors((e) => ({ ...e, username: undefined }));
            }}
            autoComplete="username"
            error={errors.username}
            inputRef={usernameRef}
          />
          <Field
            id="password"
            label="Password"
            type="password"
            value={password}
            onChange={(v) => {
              setPassword(v);
              setErrors((e) => ({ ...e, password: undefined }));
            }}
            autoComplete="current-password"
            error={errors.password}
            inputRef={passwordRef}
          />
          <button type="submit" disabled={pending}>
            {pending ? 'Logging in…' : 'Log in'}
          </button>
          <p>
            No account yet? <Link to={withNext('/register', next)}>Register</Link>.
          </p>
        </form>
      )}
    </>
  );
}
