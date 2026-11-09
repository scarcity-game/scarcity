// Client-side checks that mirror the auth service's CredentialsPolicy. The
// server stays the authority; these only give quicker, clearer feedback.
// Lengths are counted in Unicode code points (an emoji is one character), as
// the server does.

export const USERNAME_MIN = 3;
export const USERNAME_MAX = 32;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 128;
export const PASSWORD_MAX_BYTES = 72;

const USERNAME_PATTERN = /^[A-Za-z0-9_-]*$/;
const LONE_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/;

export function codePointLength(s: string): number {
  return [...s].length;
}

export function utf8ByteLength(s: string): number {
  return new TextEncoder().encode(s).length;
}

/** Returns an error message, or undefined when the username is acceptable. */
export function validateUsername(username: string): string | undefined {
  if (username === '') return 'Enter a username.';
  const length = codePointLength(username);
  if (length < USERNAME_MIN || length > USERNAME_MAX) {
    return `Username must be ${USERNAME_MIN}–${USERNAME_MAX} characters long (it is ${length}).`;
  }
  if (!USERNAME_PATTERN.test(username)) {
    return 'Username may only use the letters A–Z and a–z, the digits 0–9, "_" and "-".';
  }
  return undefined;
}

/** Returns an error message, or undefined when the password is acceptable. */
export function validateNewPassword(password: string): string | undefined {
  if (password === '') return 'Enter a password.';
  if (LONE_SURROGATE.test(password)) {
    return 'Password contains invalid Unicode (an unpaired surrogate).';
  }
  const length = codePointLength(password);
  if (length < PASSWORD_MIN) {
    return `Password must be at least ${PASSWORD_MIN} characters long (it is ${length}).`;
  }
  // Checked before the 128-character maximum because it is the binding limit:
  // any password over 128 characters is also over 72 bytes.
  const bytes = utf8ByteLength(password);
  if (bytes > PASSWORD_MAX_BYTES) {
    return (
      `Password is too long: it is ${bytes} bytes when UTF-8 encoded, and the limit is ` +
      `${PASSWORD_MAX_BYTES} bytes (bcrypt ignores anything after byte ${PASSWORD_MAX_BYTES}). ` +
      'Plain ASCII characters take 1 byte each, but accented letters, other scripts and ' +
      'emoji take 2–4 bytes each, so such a password must have fewer than ' +
      `${PASSWORD_MAX_BYTES} characters.`
    );
  }
  if (length > PASSWORD_MAX) {
    return `Password must be at most ${PASSWORD_MAX} characters long (it is ${length}).`;
  }
  return undefined;
}

export function validateConfirmPassword(password: string, confirm: string): string | undefined {
  if (confirm === '') return 'Re-enter your password.';
  if (confirm !== password) return 'Passwords do not match.';
  return undefined;
}
