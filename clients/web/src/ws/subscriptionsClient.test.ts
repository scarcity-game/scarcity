import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ResourceStore, type Resource } from '../resources/cache.ts';
import { AreaSync } from '../resources/sync.ts';
import { StubWebSocket } from '../test/stubWebSocket.ts';
import {
  SubscriptionsClient,
  defaultSocketUrl,
  type SubscriptionsHandlers,
} from './subscriptionsClient.ts';

const AREA = 'area:a';
const URL = 'wss://example.test/api/subscriptions/ws';

function make(handlers: SubscriptionsHandlers = {}, token: string | null = 'jwt-1') {
  return new SubscriptionsClient({
    url: URL,
    getToken: () => token,
    handlers,
    WebSocketImpl: StubWebSocket,
  });
}

function goLive(ws: StubWebSocket) {
  ws.open();
  ws.receive({ type: 'ready', user: 'u', username: 'alice' });
}

/** A started client whose socket is live. */
function startLive(handlers: SubscriptionsHandlers = {}) {
  const client = make(handlers);
  client.start();
  const ws = StubWebSocket.last;
  goLive(ws);
  return { client, ws };
}

/** The orbiter as the server first has it. */
const ORBITER_V1: Resource = {
  id: 'entity:o',
  resourceKind: 'entity',
  version: 1,
  children: [],
  n: 1,
};

/** Waits `ms` less 1 (no new socket yet), then 1 more (the second socket is there). */
async function expectReconnectAfter(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms - 1);
  expect(StubWebSocket.instances).toHaveLength(1);
  await vi.advanceTimersByTimeAsync(1);
  expect(StubWebSocket.instances).toHaveLength(2);
}

/** Closes the live socket and waits (in 500 ms steps) until the client opens the next; the wait in ms. */
async function reconnectDelay(): Promise<number> {
  const before = StubWebSocket.instances.length;
  StubWebSocket.last.serverClose(1006);
  let waited = 0;
  while (StubWebSocket.instances.length === before) {
    await vi.advanceTimersByTimeAsync(500);
    waited += 500;
  }
  return waited;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(console, 'debug').mockImplementation(() => {});
  StubWebSocket.reset();
});
afterEach(() => {
  vi.useRealTimers();
});

describe('SubscriptionsClient', () => {
  it('sends auth first, subscribes only after ready', () => {
    const client = make();
    client.subscribe(AREA);
    client.start();
    const ws = StubWebSocket.last;
    expect(ws.url).toBe(URL);
    expect(client.status).toBe('connecting');
    ws.open();
    expect(ws.sent).toEqual([{ type: 'auth', token: 'jwt-1' }]);
    ws.receive({ type: 'ready', user: 'u', username: 'alice' });
    expect(ws.sent).toEqual([
      { type: 'auth', token: 'jwt-1' },
      { type: 'subscribe', area: AREA },
    ]);
    expect(client.status).toBe('live');
    client.unsubscribe(AREA);
    expect(ws.sent.at(-1)).toEqual({ type: 'unsubscribe', area: AREA });
  });

  it('passes the logSeqs and part numbers of checksum, changes and reload frames to the handlers', () => {
    const h = { onChecksum: vi.fn(), onChanges: vi.fn(), onReload: vi.fn() };
    const client = make(h);
    client.start();
    const ws = StubWebSocket.last;
    goLive(ws);
    ws.receive({ type: 'checksum', area: AREA, checksum: '0000002a', logSeq: 7 });
    ws.receive({ type: 'changes', area: AREA, changes: [], fromLogSeq: 7, logSeq: 8, part: 1, parts: 2 });
    ws.receive({ type: 'changes', area: AREA, changes: [], logSeq: 'x' });
    ws.receive({ type: 'reload', area: AREA, logSeq: 12 });
    expect(h.onChecksum).toHaveBeenCalledWith(AREA, '0000002a', 7);
    expect(h.onChanges).toHaveBeenNthCalledWith(1, AREA, {
      changes: [],
      fromLogSeq: 7,
      logSeq: 8,
      part: 1,
      parts: 2,
    });
    expect(h.onChanges).toHaveBeenNthCalledWith(2, AREA, {
      changes: [],
      fromLogSeq: undefined,
      logSeq: undefined,
      part: undefined,
      parts: undefined,
    });
    expect(h.onReload).toHaveBeenCalledWith(AREA, 12);
  });

  it('dispatches checksum, changes, subscribed and unsubscribed; a digest is unknown now', () => {
    const h = {
      onChecksum: vi.fn(),
      onChanges: vi.fn(),
      onSubscribed: vi.fn(),
      onUnsubscribed: vi.fn(),
    };
    const { ws } = startLive(h);
    ws.receive({ type: 'subscribed', area: AREA });
    ws.receive({ type: 'checksum', area: AREA, checksum: '00000003', extra: 1 });
    ws.receive({ type: 'digest', area: AREA, versions: { [AREA]: 3 } });
    const changes = [{ id: 'entity:o', resourceKind: 'entity', version: 2, set: { n: 2 } }];
    ws.receive({ type: 'changes', area: AREA, changes, traceparent: 'x' });
    ws.receive({ type: 'unsubscribed', area: AREA });
    expect(h.onSubscribed).toHaveBeenCalledWith(AREA);
    expect(h.onChecksum).toHaveBeenCalledTimes(1);
    expect(h.onChecksum).toHaveBeenCalledWith(AREA, '00000003', undefined);
    expect(h.onChanges).toHaveBeenCalledWith(AREA, expect.objectContaining({ changes }));
    expect(h.onUnsubscribed).toHaveBeenCalledWith(AREA);
  });

  it('dispatches error and pong; ignores junk', () => {
    const h = { onError: vi.fn(), onPong: vi.fn() };
    const { client, ws } = startLive(h);
    ws.receive({ type: 'pong' });
    ws.receive({ type: 'error', message: 'bad area' });
    ws.receive({ type: 'mystery' });
    ws.onmessage?.({ data: 'not json' });
    expect(h.onPong).toHaveBeenCalled();
    expect(h.onError).toHaveBeenCalledWith('bad area');
    expect(client.lastError).toBe('bad area');
    expect(client.status).toBe('live'); // an error frame leaves the socket open
  });

  it('subscribe -> load by GET, then a changes frame applies an update', async () => {
    const store = new ResourceStore();
    const area = { id: AREA, resourceKind: 'area', version: 1, children: ['entity:o'] };
    const get = vi.fn(async (id: string) => (id === AREA ? area : id === 'entity:o' ? ORBITER_V1 : undefined));
    const sync = new AreaSync(AREA, store, {
      get,
      children: async () => [ORBITER_V1],
      versions: async () => ({ id: AREA, versions: {}, checksum: '00000000' }),
    });
    const client = make({
      onChecksum: (_a, c, l) => sync.handleChecksum(c, l),
      onChanges: (_a, b) => sync.handleChanges(b),
    });
    client.subscribe(AREA);
    client.start();
    const ws = StubWebSocket.last;
    goLive(ws);
    ws.receive({ type: 'subscribed', area: AREA });
    await sync.load();
    ws.receive({
      type: 'changes',
      area: AREA,
      changes: [{ id: 'entity:o', resourceKind: 'entity', version: 2, set: { n: 2 } }],
    });
    expect(store.get('entity:o')).toMatchObject({
      version: 2,
      n: 2,
    });
    expect(get).toHaveBeenCalledTimes(1);
  });

  it('close 4401 is reported and never reconnects', async () => {
    const onUnauthorized = vi.fn();
    const client = make({ onUnauthorized });
    client.start();
    goLive(StubWebSocket.last);
    StubWebSocket.last.serverClose(4401);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(client.status).toBe('closed');
    await vi.advanceTimersByTimeAsync(120_000);
    expect(StubWebSocket.instances).toHaveLength(1);
  });

  it('reconnects after another close, re-authenticates and re-subscribes', async () => {
    const client = make();
    client.subscribe(AREA);
    client.start();
    goLive(StubWebSocket.last);
    StubWebSocket.last.serverClose(1006);
    expect(client.status).toBe('reconnecting');
    expect(StubWebSocket.instances).toHaveLength(1);
    await expectReconnectAfter(1000);
    const ws = StubWebSocket.last;
    ws.open();
    expect(ws.sent).toEqual([{ type: 'auth', token: 'jwt-1' }]);
    ws.receive({ type: 'ready', user: 'u', username: 'alice' });
    expect(ws.sent.at(-1)).toEqual({ type: 'subscribe', area: AREA });
    expect(client.status).toBe('live');
  });

  it('reconnects after a socket error too', async () => {
    const client = make();
    client.start();
    StubWebSocket.last.onerror?.({});
    expect(client.lastError).toBe('WebSocket error');
    await vi.advanceTimersByTimeAsync(1000);
    expect(StubWebSocket.instances).toHaveLength(2);
  });

  it('backs off 1s, 2s, 4s ... capped at 30s, and resets on ready', async () => {
    const client = make();
    client.start();
    const delays: number[] = [];
    for (let i = 0; i < 8; i++) delays.push(await reconnectDelay());
    expect(delays).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000]);
    goLive(StubWebSocket.last);
    const before = StubWebSocket.instances.length;
    StubWebSocket.last.serverClose(1006);
    await vi.advanceTimersByTimeAsync(1000);
    expect(StubWebSocket.instances).toHaveLength(before + 1);
  });

  it('pings every 25 s while open, and stops when closed', async () => {
    const client = make();
    client.start();
    const ws = StubWebSocket.last;
    goLive(ws);
    await vi.advanceTimersByTimeAsync(24_999);
    expect(ws.types()).not.toContain('ping');
    await vi.advanceTimersByTimeAsync(1);
    expect(ws.types().filter((t) => t === 'ping')).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(25_000);
    expect(ws.types().filter((t) => t === 'ping')).toHaveLength(2);
    ws.serverClose(1006);
    await vi.advanceTimersByTimeAsync(24_000);
    expect(ws.types().filter((t) => t === 'ping')).toHaveLength(2);
  });

  it('stop() closes the socket without reconnecting', async () => {
    const client = make();
    client.start();
    const ws = StubWebSocket.last;
    goLive(ws);
    client.stop();
    expect(ws.closed).toBe(true);
    expect(client.status).toBe('closed');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(StubWebSocket.instances).toHaveLength(1);
  });

  it('without a token it reports unauthorized instead of sending auth', () => {
    const onUnauthorized = vi.fn();
    const client = make({ onUnauthorized }, null);
    client.start();
    StubWebSocket.last.open();
    expect(onUnauthorized).toHaveBeenCalled();
    expect(StubWebSocket.last.sent).toEqual([]);
  });
});

describe('defaultSocketUrl', () => {
  it('uses wss on https and ws on http', () => {
    expect(defaultSocketUrl({ protocol: 'https:', host: 'a.example' })).toBe(
      'wss://a.example/api/subscriptions/ws',
    );
    expect(defaultSocketUrl({ protocol: 'http:', host: 'localhost:5173' })).toBe(
      'ws://localhost:5173/api/subscriptions/ws',
    );
  });
});
