import { useRef, useState, type FormEvent, type RefObject } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { errorMessage } from '../api/client.ts';
import { useAuth, type AuthContextValue } from '../auth/AuthProvider.tsx';
import { safeReturnTo, withNext } from '../auth/returnTo.ts';
import { SessionCheck } from '../auth/SessionCheck.tsx';
import { Field, FormError } from '../forms/Field.tsx';

interface Errors {
  username?: string;
  password?: string;
}

export const INVALID_CREDENTIALS = 'Invalid username or password';

interface LoginFields {
  username: string;
  password: string;
  errors: Errors;
  setErrors: (errors: Errors) => void;
  refs: { username: RefObject<HTMLInputElement>; password: RefObject<HTMLInputElement> };
  onUsername: (value: string) => void;
  onPassword: (value: string) => void;
}

function useLoginFields(): LoginFields {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const refs = {
    username: useRef<HTMLInputElement>(null),
    password: useRef<HTMLInputElement>(null),
  };
  const onUsername = (value: string) => {
    setUsername(value);
    setErrors((e) => ({ ...e, username: undefined }));
  };
  const onPassword = (value: string) => {
    setPassword(value);
    setErrors((e) => ({ ...e, password: undefined }));
  };
  return { username, password, errors, setErrors, refs, onUsername, onPassword };
}

// Only presence is checked here: any wrong credentials get the server's 401.
function missingFields(name: string, password: string): Errors {
  return {
    username: name === '' ? 'Enter your username.' : undefined,
    password: password === '' ? 'Enter your password.' : undefined,
  };
}

function firstInvalidRef(found: Errors, refs: LoginFields['refs']) {
  if (found.username) return refs.username;
  return found.password ? refs.password : null;
}

function useLoginSubmit(fields: LoginFields, login: AuthContextValue['login']) {
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const name = fields.username.trim();
    const found = missingFields(name, fields.password);
    fields.setErrors(found);
    setFormError(null);
    const invalid = firstInvalidRef(found, fields.refs);
    if (invalid) {
      invalid.current?.focus();
      return;
    }

    setPending(true);
    try {
      await login({ username: name, password: fields.password });
      // The status change above redirects.
    } catch (err) {
      setFormError(errorMessage(err, { unauthorized: INVALID_CREDENTIALS }));
      setPending(false);
    }
  }

  return { formError, pending, onSubmit };
}

function LoginFieldInputs({ fields }: { fields: LoginFields }) {
  return (
    <>
      <Field
        id="username"
        label="Username"
        type="text"
        value={fields.username}
        onChange={fields.onUsername}
        autoComplete="username"
        error={fields.errors.username}
        inputRef={fields.refs.username}
      />
      <Field
        id="password"
        label="Password"
        type="password"
        value={fields.password}
        onChange={fields.onPassword}
        autoComplete="current-password"
        error={fields.errors.password}
        inputRef={fields.refs.password}
      />
    </>
  );
}

export function LoginView() {
  const { status, endReason, login } = useAuth();
  const [params] = useSearchParams();
  const next = safeReturnTo(params.get('next'));
  const fields = useLoginFields();
  const { formError, pending, onSubmit } = useLoginSubmit(fields, login);

  // Logged in (including right after a successful submit): go to the target.
  if (status === 'authenticated') return <Navigate to={next} replace />;

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
          <LoginFieldInputs fields={fields} />
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
