import type { WebSocketLike } from '../ws/subscriptionsClient.ts';

/** A hand-written WebSocket stand-in that records what the client sends. */
export class StubWebSocket implements WebSocketLike {
  static instances: StubWebSocket[] = [];
  static reset(): void {
    StubWebSocket.instances = [];
  }
  static get last(): StubWebSocket {
    return StubWebSocket.instances[StubWebSocket.instances.length - 1]!;
  }

  onopen: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: unknown }) => void) | null = null;
  onclose: ((ev: { code: number; reason?: string }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  sent: Record<string, unknown>[] = [];
  closed = false;

  constructor(readonly url: string) {
    StubWebSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(JSON.parse(data) as Record<string, unknown>);
  }

  close(code = 1005): void {
    this.closed = true;
    this.onclose?.({ code });
  }

  // Server side helpers.
  open(): void {
    this.onopen?.({});
  }
  receive(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
  serverClose(code: number): void {
    this.closed = true;
    this.onclose?.({ code });
  }
  types(): unknown[] {
    return this.sent.map((f) => f.type);
  }
}
