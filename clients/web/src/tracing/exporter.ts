import { ExportResultCode, type ExportResult } from '@opentelemetry/core';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace';

/** How long exports are skipped after one fails (429, 5xx, network error). */
export const EXPORT_COOLDOWN_MS = 60_000;

/**
 * Wraps an exporter: after a failed export, every export in the next
 * `cooldownMs` is dropped without a request. The /otlp endpoint has one rate
 * limit (600 requests/min) shared by all clients, and Envoy's local limit
 * refills per minute, so retrying sooner only adds rejected requests. Spans
 * are dropped, never queued: tracing is best effort.
 */
export class CooldownExporter implements SpanExporter {
  private readonly inner: SpanExporter;
  private readonly cooldownMs: number;
  private readonly now: () => number;
  private pausedUntil = 0;

  constructor(inner: SpanExporter, cooldownMs = EXPORT_COOLDOWN_MS, now: () => number = Date.now) {
    this.inner = inner;
    this.cooldownMs = cooldownMs;
    this.now = now;
  }

  export(spans: ReadableSpan[], resultCallback: (result: ExportResult) => void): void {
    if (this.now() < this.pausedUntil) {
      resultCallback({ code: ExportResultCode.FAILED, error: new Error('export paused') });
      return;
    }
    const done = (result: ExportResult) => {
      if (result.code !== ExportResultCode.SUCCESS) this.pausedUntil = this.now() + this.cooldownMs;
      resultCallback(result);
    };
    try {
      this.inner.export(spans, done);
    } catch (error) {
      done({ code: ExportResultCode.FAILED, error: error instanceof Error ? error : undefined });
    }
  }

  async shutdown(): Promise<void> {
    try {
      await this.inner.shutdown();
    } catch {
      // ignore: nothing to do with a failed shutdown
    }
  }

  async forceFlush(): Promise<void> {
    try {
      await this.inner.forceFlush?.();
    } catch {
      // ignore
    }
  }
}
