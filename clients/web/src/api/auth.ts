// Calls to the auth service. Contract: services/auth/AGENTS.md.
import { ApiError, apiRequest } from './client.ts';

export interface Credentials {
  username: string;
  password: string;
}

export interface User {
  id: string;
  username: string;
  /** ISO-8601 UTC instant. */
  createdAt: string;
}

export interface TokenResponse {
  token: string;
  tokenType: 'Bearer';
  /** ISO-8601 UTC instant; the JWT's `exp`. */
  expiresAt: string;
}

/** POST /api/auth/register: 201 user; 409 username taken; 400 validation. */
export async function register(credentials: Credentials, signal?: AbortSignal): Promise<User> {
  const body = await apiRequest<unknown>('/api/auth/register', {
    method: 'POST',
    body: credentials,
    signal,
  });
  return expectUser(body, 201);
}

/** POST /api/auth/login: 200 token; 401 bad credentials. */
export async function login(credentials: Credentials, signal?: AbortSignal): Promise<TokenResponse> {
  const body = await apiRequest<unknown>('/api/auth/login', {
    method: 'POST',
    body: credentials,
    signal,
  });
  if (
    isObject(body) &&
    typeof body.token === 'string' &&
    body.token !== '' &&
    typeof body.expiresAt === 'string' &&
    !Number.isNaN(Date.parse(body.expiresAt))
  ) {
    return { token: body.token, tokenType: 'Bearer', expiresAt: body.expiresAt };
  }
  throw invalid(200);
}

/** GET /api/auth/me: 200 user; 401 without a valid token. */
export async function me(token: string, signal?: AbortSignal): Promise<User> {
  const body = await apiRequest<unknown>('/api/auth/me', { token, signal });
  return expectUser(body, 200);
}

export function isUser(value: unknown): value is User {
  return (
    isObject(value) &&
    typeof value.id === 'string' &&
    typeof value.username === 'string' &&
    typeof value.createdAt === 'string'
  );
}

function expectUser(body: unknown, status: number): User {
  if (!isUser(body)) throw invalid(status);
  return { id: body.id, username: body.username, createdAt: body.createdAt };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function invalid(status: number): ApiError {
  return new ApiError({
    kind: 'invalid-response',
    status,
    message: 'Response did not match the auth contract',
  });
}
