import { describe, expect, it } from 'vitest';
import {
  utf8ByteLength,
  validateConfirmPassword,
  validateNewPassword,
  validateUsername,
} from './validation.ts';

describe('username validation', () => {
  it.each(['abc', 'a'.repeat(32), 'Alice_the-2nd'])('accepts %s', (name) => {
    expect(validateUsername(name)).toBeUndefined();
  });

  it('requires a value', () => {
    expect(validateUsername('')).toBe('Enter a username.');
  });

  it.each(['ab', 'a'.repeat(33)])('rejects the length of %s', (name) => {
    expect(validateUsername(name)).toMatch(/must be 3–32 characters/);
  });

  it.each(['al ice', 'alice!', 'älice', 'al.ice'])('rejects the characters in %s', (name) => {
    expect(validateUsername(name)).toMatch(/may only use/);
  });
});

describe('password validation', () => {
  it('accepts 8 to 72 ASCII characters', () => {
    expect(validateNewPassword('a'.repeat(8))).toBeUndefined();
    expect(validateNewPassword('a'.repeat(72))).toBeUndefined();
  });

  it('rejects short passwords, counting code points (an emoji is one character)', () => {
    expect(validateNewPassword('abcdefg')).toMatch(/at least 8 characters/);
    expect(validateNewPassword('🐉🐉🐉🐉🐉🐉🐉')).toMatch(/at least 8 characters long \(it is 7\)/);
    expect(validateNewPassword('🐉🐉🐉🐉🐉🐉🐉🐉')).toBeUndefined(); // 8 chars, 32 bytes
  });

  it('rejects more than 72 UTF-8 bytes and says why', () => {
    expect(validateNewPassword('a'.repeat(73))).toMatch(/73 bytes .* limit is 72 bytes/);
    // 37 x "é" (2 bytes each) = 74 bytes, but only 37 characters.
    const accented = 'é'.repeat(37);
    expect(utf8ByteLength(accented)).toBe(74);
    const message = validateNewPassword(accented);
    expect(message).toMatch(/74 bytes when UTF-8 encoded/);
    expect(message).toMatch(/accented letters, other scripts and emoji take 2–4 bytes/);
    expect(message).toMatch(/bcrypt/);
    // 18 dragons = 72 bytes: allowed; 19 = 76 bytes: rejected.
    expect(validateNewPassword('🐉'.repeat(18))).toBeUndefined();
    expect(validateNewPassword('🐉'.repeat(19))).toMatch(/76 bytes/);
  });

  it('rejects lone surrogates', () => {
    expect(validateNewPassword('abcdefgh\uD800')).toMatch(/invalid Unicode/);
  });

  it('checks the confirmation', () => {
    expect(validateConfirmPassword('secret-pw', '')).toBe('Re-enter your password.');
    expect(validateConfirmPassword('secret-pw', 'secret-pW')).toBe('Passwords do not match.');
    expect(validateConfirmPassword('secret-pw', 'secret-pw')).toBeUndefined();
  });
});
