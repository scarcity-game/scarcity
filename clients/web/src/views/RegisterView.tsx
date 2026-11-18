import { useRef, useState, type FormEvent, type ReactNode, type RefObject } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { errorMessage, isApiError } from '../api/client.ts';
import type { Credentials } from '../api/auth.ts';
import { useAuth, type AuthContextValue } from '../auth/AuthProvider.tsx';
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

type FieldRefs = Record<keyof Errors, RefObject<HTMLInputElement>>;

interface RegisterFields {
  username: string;
  password: string;
  confirm: string;
  errors: Errors;
  setErrors: (errors: Errors) => void;
  refs: FieldRefs;
  onUsername: (value: string) => void;
  onPassword: (value: string) => void;
  onConfirm: (value: string) => void;
}

function useRegisterFields(): RegisterFields {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const refs = {
    username: useRef<HTMLInputElement>(null),
    password: useRef<HTMLInputElement>(null),
    confirm: useRef<HTMLInputElement>(null),
  };
  const edit = (field: keyof Errors, setter: (v: string) => void) => (value: string) => {
    setter(value);
    setErrors((e) => ({ ...e, [field]: undefined }));
  };
  return {
    username,
    password,
    confirm,
    errors,
    setErrors,
    refs,
    onUsername: edit('username', setUsername),
    onPassword: edit('password', setPassword),
    onConfirm: edit('confirm', setConfirm),
  };
}

function validateFields(fields: RegisterFields): Errors {
  return {
    username: validateUsername(fields.username),
    password: validateNewPassword(fields.password),
    confirm: validateConfirmPassword(fields.password, fields.confirm),
  };
}

// What the player sees when registering failed: the message, and the fields it is about.
function showRegisterError(err: unknown, fields: RegisterFields): ReactNode {
  if (isApiError(err) && err.status === 409) {
    // Username taken: mark the field too, and put the player back in it.
    fields.setErrors({ username: err.detail ?? USERNAME_TAKEN });
    fields.refs.username.current?.focus();
  } else if (isApiError(err)) {
    fields.setErrors(serverFieldErrors(err.fieldErrors));
  }
  return errorMessage(err);
}

function loginFailedMessage(err: unknown, next: string): ReactNode {
  return (
    <>
      Your account was created, but logging in failed:{' '}
      {errorMessage(err, { unauthorized: INVALID_CREDENTIALS })}{' '}
      <Link to={withNext('/login', next)}>Log in</Link> to continue.
    </>
  );
}

// Validates the fields; focuses the first invalid one. False if any is invalid.
function checkFields(fields: RegisterFields): boolean {
  const found = validateFields(fields);
  fields.setErrors(found);
  const firstInvalid = (['username', 'password', 'confirm'] as const).find((f) => found[f]);
  if (firstInvalid === undefined) return true;
  fields.refs[firstInvalid].current?.focus();
  return false;
}

type Auth = Pick<AuthContextValue, 'register' | 'login'>;

// Registers, then logs in; what to show if either failed, else null.
async function attempt(
  { register, login }: Auth,
  credentials: Credentials,
  fields: RegisterFields,
  next: string,
): Promise<ReactNode> {
  try {
    await register(credentials);
  } catch (err) {
    return showRegisterError(err, fields);
  }
  try {
    await login(credentials);
    // The status change above redirects.
  } catch (err) {
    return loginFailedMessage(err, next);
  }
  return null;
}

function useRegisterSubmit(fields: RegisterFields, next: string) {
  const auth = useAuth();
  const [formError, setFormError] = useState<ReactNode>(null);
  const [pending, setPending] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setFormError(null);
    if (!checkFields(fields)) return;
    setPending(true);
    const credentials = { username: fields.username, password: fields.password };
    const failure = await attempt(auth, credentials, fields, next);
    if (failure !== null) {
      setFormError(failure);
      setPending(false);
    }
  }

  return { formError, pending, onSubmit };
}

type FieldProps = { fields: RegisterFields };

function UsernameField({ fields }: FieldProps) {
  return (
    <Field
      id="username"
      label="Username"
      type="text"
      value={fields.username}
      onChange={fields.onUsername}
      autoComplete="username"
      hint={`${USERNAME_MIN}–${USERNAME_MAX} characters: letters, digits, "_" and "-".`}
      error={fields.errors.username}
      inputRef={fields.refs.username}
    />
  );
}

const PASSWORD_HINT =
  `At least ${PASSWORD_MIN} characters and at most ${PASSWORD_MAX_BYTES} bytes ` +
  'in UTF-8 (accented letters, other scripts and emoji take 2–4 bytes each).';

function PasswordField({ fields }: FieldProps) {
  return (
    <Field
      id="password"
      label="Password"
      type="password"
      value={fields.password}
      onChange={fields.onPassword}
      autoComplete="new-password"
      hint={PASSWORD_HINT}
      error={fields.errors.password}
      inputRef={fields.refs.password}
    />
  );
}

function ConfirmField({ fields }: FieldProps) {
  return (
    <Field
      id="confirm-password"
      label="Confirm password"
      type="password"
      value={fields.confirm}
      onChange={fields.onConfirm}
      autoComplete="new-password"
      error={fields.errors.confirm}
      inputRef={fields.refs.confirm}
    />
  );
}

export function RegisterView() {
  const { status } = useAuth();
  const [params] = useSearchParams();
  const next = safeReturnTo(params.get('next'));
  const fields = useRegisterFields();
  const { formError, pending, onSubmit } = useRegisterSubmit(fields, next);

  // Logged in (including right after register + auto-login): go to the target.
  if (status === 'authenticated') return <Navigate to={next} replace />;

  return (
    <>
      <h1>Register</h1>
      {status === 'checking' ? (
        <SessionCheck />
      ) : (
        <form className="auth-form" onSubmit={onSubmit} noValidate>
          <FormError message={formError} />
          <UsernameField fields={fields} />
          <PasswordField fields={fields} />
          <ConfirmField fields={fields} />
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
