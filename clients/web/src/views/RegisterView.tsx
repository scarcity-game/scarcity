import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { errorMessage, isApiError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import { safeReturnTo, withNext } from '../auth/returnTo.ts';
import { SessionCheck } from '../auth/SessionCheck.tsx';
import {
  PASSWORD_MAX_BYTES,
  PASSWORD_MIN,
  USERNAME_MAX,
  USERNAME_MIN,
  validateConfirmPassword,
  validateNewPassword,
  validateUsername,
} from '../auth/validation.ts';
import { Field, FormError } from '../forms/Field.tsx';
import { INVALID_CREDENTIALS } from './LoginView.tsx';

interface Errors {
  username?: string;
  password?: string;
  confirm?: string;
}

const USERNAME_TAKEN = 'That username is taken.';

const FIELD_LABELS: Record<string, keyof Errors> = { username: 'username', password: 'password' };

export function RegisterView() {
  const { status, register, login } = useAuth();
  const [params] = useSearchParams();
  const next = safeReturnTo(params.get('next'));

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<ReactNode>(null);
  const [pending, setPending] = useState(false);
  const refs = {
    username: useRef<HTMLInputElement>(null),
    password: useRef<HTMLInputElement>(null),
    confirm: useRef<HTMLInputElement>(null),
  };

  // Logged in (including right after register + auto-login): go to the target.
  if (status === 'authenticated') return <Navigate to={next} replace />;

  function edit(field: keyof Errors, setter: (v: string) => void) {
    return (value: string) => {
      setter(value);
      setErrors((e) => ({ ...e, [field]: undefined }));
    };
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const found: Errors = {
      username: validateUsername(username),
      password: validateNewPassword(password),
      confirm: validateConfirmPassword(password, confirm),
    };
    setErrors(found);
    setFormError(null);
    const firstInvalid = (['username', 'password', 'confirm'] as const).find((f) => found[f]);
    if (firstInvalid) {
      refs[firstInvalid].current?.focus();
      return;
    }

    setPending(true);
    const credentials = { username, password };
    try {
      await register(credentials);
    } catch (err) {
      setFormError(errorMessage(err));
      if (isApiError(err) && err.status === 409) {
        // Username taken: mark the field too, and put the player back in it.
        setErrors({ username: err.detail ?? USERNAME_TAKEN });
        refs.username.current?.focus();
      } else if (isApiError(err)) {
        setErrors(serverFieldErrors(err.fieldErrors));
      }
      setPending(false);
      return;
    }
    try {
      await login(credentials);
      // The status change above redirects.
    } catch (err) {
      setFormError(
        <>
          Your account was created, but logging in failed:{' '}
          {errorMessage(err, { unauthorized: INVALID_CREDENTIALS })}{' '}
          <Link to={withNext('/login', next)}>Log in</Link> to continue.
        </>,
      );
      setPending(false);
    }
  }

  return (
    <>
      <h1>Register</h1>
      {status === 'checking' ? (
        <SessionCheck />
      ) : (
        <form className="auth-form" onSubmit={onSubmit} noValidate>
          <FormError message={formError} />
          <Field
            id="username"
            label="Username"
            type="text"
            value={username}
            onChange={edit('username', setUsername)}
            autoComplete="username"
            hint={`${USERNAME_MIN}–${USERNAME_MAX} characters: letters, digits, "_" and "-".`}
            error={errors.username}
            inputRef={refs.username}
          />
          <Field
            id="password"
            label="Password"
            type="password"
            value={password}
            onChange={edit('password', setPassword)}
            autoComplete="new-password"
            hint={
              `At least ${PASSWORD_MIN} characters and at most ${PASSWORD_MAX_BYTES} bytes ` +
              'in UTF-8 (accented letters, other scripts and emoji take 2–4 bytes each).'
            }
            error={errors.password}
            inputRef={refs.password}
          />
          <Field
            id="confirm-password"
            label="Confirm password"
            type="password"
            value={confirm}
            onChange={edit('confirm', setConfirm)}
            autoComplete="new-password"
            error={errors.confirm}
            inputRef={refs.confirm}
          />
          <button type="submit" disabled={pending}>
            {pending ? 'Creating account…' : 'Register'}
          </button>
          <p>
            Already have an account? <Link to={withNext('/login', next)}>Log in</Link>.
          </p>
        </form>
      )}
    </>
  );
}

// The auth service's 400 carries `errors: [{field, message}]`, with messages
// like "must be 3-32 characters long". Show them next to their fields.
function serverFieldErrors(fieldErrors: { field: string; message: string }[]): Errors {
  const errors: Errors = {};
  for (const { field, message } of fieldErrors) {
    const key = FIELD_LABELS[field];
    if (!key) continue;
    const text = `${key === 'username' ? 'Username' : 'Password'} ${message}.`;
    errors[key] = errors[key] ? `${errors[key]} ${text}` : text;
  }
  return errors;
}
