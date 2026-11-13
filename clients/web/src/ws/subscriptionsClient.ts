// Client for the subscriptions WebSocket (protocol: /AGENTS.md, "WebSocket
// protocol"). Framework free; the WebSocket constructor and timers can be
// injected for tests.

export type ConnectionStatus = 'connecting' | 'live' | 'reconnecting' | 'closed';

export interface WireChange {
  id: string;
  type?: string;
  version?: number;
  patch?: Record<string, unknown> | null;
  children?: string[];
  deleted?: boolean;
}

export interface ServerMessage {
  type: string;
  area?: string;
  versions?: Record<string, number>;
  changes?: WireChange[];
  message?: string;
}

export interface SubscriptionsHandlers {
  onStatus?(status: ConnectionStatus, lastError: string | null): void;
  onDigest?(area: string, versions: Record<string, number>): void;
  onChanges?(area: string, changes: WireChange[]): void;
  onSubscribed?(area: string): void;
  onUnsubscribed?(area: string): void;
  onError?(message: string): void;
  onPong?(): void;
  /** Close code 4401: the token is missing, invalid or expired. No reconnect follows. */
  onUnauthorized?(): void;
}

export interface WebSocketLike {
  onopen: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: unknown }) => void) | null;
  onclose: ((ev: { code: number; reason?: string }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface Timers {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  setInterval(fn: () => void, ms: number): unknown;
  clearInterval(handle: unknown): void;
}

export interface SubscriptionsClientOptions {
  url: string;
  getToken: () => string | null;
  handlers?: SubscriptionsHandlers;
  WebSocketImpl?: new (url: string) => WebSocketLike;
  timers?: Timers;
}

export const PING_INTERVAL_MS = 25_000;
export const BACKOFF_START_MS = 1_000;
export const BACKOFF_MAX_MS = 30_000;
export const CLOSE_UNAUTHORIZED = 4401;

// Looked up at call time so fake timers installed later are honoured.
const globalTimers: Timers = {
  setTimeout: (fn, ms) => globalThis.setTimeout(fn, ms),
  clearTimeout: (h) => globalThis.clearTimeout(h as number),
  setInterval: (fn, ms) => globalThis.setInterval(fn, ms),
  clearInterval: (h) => globalThis.clearInterval(h as number),
};

export function defaultSocketUrl(loc: Pick<Location, 'protocol' | 'host'> = window.location) {
  return `${loc.protocol === 'https:' ? 'wss' : 'ws'}://${loc.host}/api/subscriptions/ws`;
}

export class SubscriptionsClient {
  status: ConnectionStatus = 'closed';
  lastError: string | null = null;

  private socket: WebSocketLike | null = null;
  private ready = false;
  private stopped = true;
  private backoff = BACKOFF_START_MS;
  private reconnectTimer: unknown = null;
  private pingTimer: unknown = null;
  private attempts = 0;
  private readonly wanted = new Set<string>();
  private readonly timers: Timers;

  constructor(private readonly options: SubscriptionsClientOptions) {
    this.timers = options.timers ?? globalTimers;
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.attempts = 0;
    this.backoff = BACKOFF_START_MS;
    this.connect();
  }

  /** Closes the socket for good (e.g. on logout or when leaving the page). */
  stop(): void {
    this.stopped = true;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    this.ready = false;
    if (socket) {
      detach(socket);
      try {
        socket.close(1000);
      } catch {
        // already closed
      }
    }
    this.setStatus('closed');
  }

  /** Remembered across reconnects; sent once the server said `ready`. */
  subscribe(area: string): void {
    this.wanted.add(area);
    if (this.ready) this.send({ type: 'subscribe', area });
  }

  unsubscribe(area: string): void {
    this.wanted.delete(area);
    if (this.ready) this.send({ type: 'unsubscribe', area });
  }

  private connect(): void {
    const token = this.options.getToken();
    this.setStatus(this.attempts === 0 ? 'connecting' : 'reconnecting');
    this.attempts++;
    const Impl =
      this.options.WebSocketImpl ??
      (globalThis.WebSocket as unknown as new (url: string) => WebSocketLike);
    let socket: WebSocketLike;
    try {
      socket = new Impl(this.options.url);
    } catch (err) {
      this.lastError = err instanceof Error ? err.message : String(err);
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    let dead = false;
    const lost = (code: number | null) => {
      if (dead || this.socket !== socket) return;
      dead = true;
      this.socket = null;
      this.ready = false;
      this.clearTimers();
      detach(socket);
      console.debug('[ws] closed', code);
      if (code === CLOSE_UNAUTHORIZED) {
        this.stopped = true;
        this.setStatus('closed');
        this.options.handlers?.onUnauthorized?.();
        return;
      }
      if (!this.stopped) this.scheduleReconnect();
    };
    socket.onopen = () => {
      if (token === null) {
        // No session: nothing to authenticate with.
        this.lastError = 'No session token';
        this.stopped = true;
        detach(socket);
        this.socket = null;
        try {
          socket.close(1000);
        } catch {
          // ignore
        }
        this.setStatus('closed');
        this.options.handlers?.onUnauthorized?.();
        return;
      }
      console.debug('[ws] open, sending auth');
      this.send({ type: 'auth', token });
    };
    socket.onmessage = (ev) => this.onMessage(ev.data);
    socket.onerror = () => {
      this.lastError = 'WebSocket error';
      console.debug('[ws] error');
      try {
        socket.close();
      } catch {
        // ignore
      }
      lost(null);
    };
    socket.onclose = (ev) => lost(ev.code);
  }

  private onMessage(data: unknown): void {
    if (typeof data !== 'string') return;
    let msg: ServerMessage;
    try {
      msg = JSON.parse(data) as ServerMessage;
    } catch {
      return;
    }
    if (typeof msg !== 'object' || msg === null || typeof msg.type !== 'string') return;
    console.debug('[ws] <-', msg.type, msg.area ?? '');
    const h = this.options.handlers;
    switch (msg.type) {
      case 'ready':
        this.ready = true;
        this.backoff = BACKOFF_START_MS;
        this.attempts = 1;
        this.lastError = null;
        this.setStatus('live');
        if (this.pingTimer !== null) this.timers.clearInterval(this.pingTimer);
        this.pingTimer = this.timers.setInterval(
          () => this.send({ type: 'ping' }),
          PING_INTERVAL_MS,
        );
        for (const area of this.wanted) this.send({ type: 'subscribe', area });
        break;
      case 'subscribed':
        if (msg.area) h?.onSubscribed?.(msg.area);
        break;
      case 'unsubscribed':
        if (msg.area) h?.onUnsubscribed?.(msg.area);
        break;
      case 'digest':
        if (msg.area && msg.versions) h?.onDigest?.(msg.area, msg.versions);
        break;
      case 'changes':
        if (msg.area && Array.isArray(msg.changes)) h?.onChanges?.(msg.area, msg.changes);
        break;
      case 'pong':
        h?.onPong?.();
        break;
      case 'error':
        this.lastError = msg.message ?? 'Server error';
        h?.onError?.(this.lastError);
        this.setStatus(this.status);
        break;
      default:
        break; // unknown frame types are ignored
    }
  }

  private send(frame: Record<string, unknown>): void {
    try {
      this.socket?.send(JSON.stringify(frame));
      console.debug('[ws] ->', frame.type, frame.area ?? '');
    } catch {
      // The close event will follow and trigger the reconnect.
    }
  }

  private scheduleReconnect(): void {
    this.setStatus('reconnecting');
    const delay = this.backoff;
    this.backoff = Math.min(this.backoff * 2, BACKOFF_MAX_MS);
    console.debug('[ws] reconnecting in', delay, 'ms');
    this.reconnectTimer = this.timers.setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.stopped) this.connect();
    }, delay);
  }

  private clearTimers(): void {
    if (this.pingTimer !== null) this.timers.clearInterval(this.pingTimer);
    if (this.reconnectTimer !== null) this.timers.clearTimeout(this.reconnectTimer);
    this.pingTimer = null;
    this.reconnectTimer = null;
  }

  private setStatus(status: ConnectionStatus): void {
    this.status = status;
    this.options.handlers?.onStatus?.(status, this.lastError);
  }
}

function detach(socket: WebSocketLike): void {
  socket.onopen = null;
  socket.onmessage = null;
  socket.onclose = null;
  socket.onerror = null;
}
