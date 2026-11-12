// A small typed HTTP client for the Scarcity services.
//
// Always same-origin relative URLs (`/api/<service>/...`): in production the
// Gateway routes them, in dev Vite proxies them. Errors are RFC 7807
// `application/problem+json`; they are parsed into an `ApiError`.

/** One entry of the `errors` extension the auth service adds to 400 problems. */
export interface ProblemFieldError {
  field: string;
  message: string;
}

/**
 * - `http`: the server answered with a non-2xx status (`status` is set).
 * - `network`: no response at all (offline, DNS, connection refused, CORS...).
 * - `invalid-response`: a 2xx response whose body was not the expected JSON.
 */
export type ApiErrorKind = 'http' | 'network' | 'invalid-response';

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  /** HTTP status, or 0 when there was no response (`kind === 'network'`). */
  readonly status: number;
  /** problem+json `type`, `title` and `detail`, when the body had them. */
  readonly type?: string;
  readonly title?: string;
  readonly detail?: string;
  /** problem+json `errors` extension (field-level validation messages), if any. */
  readonly fieldErrors: ProblemFieldError[];
  /** The whole parsed problem body, for extension members not modelled above. */
  readonly problem?: Record<string, unknown>;

  constructor(init: {
    kind: ApiErrorKind;
    status: number;
    message?: string;
    problem?: Record<string, unknown>;
    cause?: unknown;
  }) {
    const problem = init.problem;
    const title = stringField(problem, 'title');
    const detail = stringField(problem, 'detail');
    super(init.message ?? detail ?? title ?? `HTTP ${init.status}`, { cause: init.cause });
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status;
    this.type = stringField(problem, 'type');
    this.title = title;
    this.detail = detail;
    this.fieldErrors = parseFieldErrors(problem?.['errors']);
    this.problem = problem;
  }
}

export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Serialised as JSON. */
  body?: unknown;
  /** Sent as `Authorization: Bearer <token>` when set. */
  token?: string | null;
  signal?: AbortSignal;
  /**
   * Sent as `If-None-Match` (pass the quoted ETag, e.g. `"41"`). A 304 answer
   * then resolves to `undefined` instead of throwing: "keep what you have".
   */
  ifNoneMatch?: string;
}

/**
 * Sends a JSON request and returns the parsed JSON body (undefined for 204).
 * Throws `ApiError` for non-2xx responses, network failures and unparseable
 * success bodies. An abort is rethrown as-is (a DOMException named AbortError).
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers({ Accept: 'application/json, application/problem+json' });
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (options.ifNoneMatch) {
    headers.set('If-None-Match', options.ifNoneMatch);
  }
  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`);
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? (options.body === undefined ? 'GET' : 'POST'),
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (err) {
    if (isAbortError(err)) throw err;
    throw new ApiError({ kind: 'network', status: 0, message: 'Network error', cause: err });
  }

  if (response.status === 304 && options.ifNoneMatch) {
    return undefined as T;
  }
  if (!response.ok) {
    throw new ApiError({
      kind: 'http',
      status: response.status,
      problem: await readProblem(response),
    });
  }

  if (response.status === 204) {
    return undefined as T;
  }
  try {
    return (await response.json()) as T;
  } catch (err) {
    if (isAbortError(err)) throw err;
    throw new ApiError({
      kind: 'invalid-response',
      status: response.status,
      message: 'Response was not valid JSON',
      cause: err,
    });
  }
}

export function isAbortError(err: unknown): boolean {
  return (
    typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError'
  );
}

// Reads a problem+json (or any JSON object) error body. Anything else (an
// HTML error page from a proxy, an empty body) yields undefined.
async function readProblem(response: Response): Promise<Record<string, unknown> | undefined> {
  const contentType = response.headers.get('Content-Type') ?? '';
  if (!/[/+]json\b/i.test(contentType)) return undefined;
  try {
    const body: unknown = await response.json();
    return typeof body === 'object' && body !== null && !Array.isArray(body)
      ? (body as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

function stringField(obj: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = obj?.[key];
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function parseFieldErrors(value: unknown): ProblemFieldError[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((entry: unknown) => {
    if (typeof entry !== 'object' || entry === null) return [];
    const { field, message } = entry as Record<string, unknown>;
    return typeof field === 'string' && typeof message === 'string' ? [{ field, message }] : [];
  });
}

export const NETWORK_ERROR_MESSAGE =
  'Could not reach the server. Check your connection and try again.';

/**
 * A user-facing message for an error from `apiRequest`: the problem `detail`,
 * else its `title`, else a generic message. `unauthorized` replaces the
 * generic fallback for a 401 without detail (e.g. "Invalid username or password.").
 */
export function errorMessage(err: unknown, options: { unauthorized?: string } = {}): string {
  if (!isApiError(err)) return 'Something went wrong. Please try again.';
  switch (err.kind) {
    case 'network':
      return NETWORK_ERROR_MESSAGE;
    case 'invalid-response':
      return 'The server sent an unexpected response. Please try again.';
    case 'http':
      if (err.detail) return err.detail;
      if (err.status === 401 && options.unauthorized) return options.unauthorized;
      if (err.title) return err.title;
      return `Something went wrong (HTTP ${err.status}). Please try again.`;
  }
}
