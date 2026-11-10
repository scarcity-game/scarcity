import { ExportResultCode, type ExportResult } from '@opentelemetry/core';
import type { ReadableSpan, SpanExporter } from '@opentelemetry/sdk-trace';
import { describe, expect, it, vi } from 'vitest';
import { CooldownExporter } from './exporter.ts';

function fakeExporter(results: Array<ExportResult | Error>) {
  const exportFn = vi.fn((_spans: ReadableSpan[], callback: (result: ExportResult) => void) => {
    const next = results.shift() ?? { code: ExportResultCode.SUCCESS };
    if (next instanceof Error) throw next;
    callback(next);
  });
  const inner: SpanExporter = { export: exportFn, shutdown: vi.fn(async () => undefined) };
  return { inner, exportFn };
}

function exportOnce(exporter: SpanExporter): Promise<ExportResult> {
  return new Promise((resolve) => exporter.export([], resolve));
}

describe('CooldownExporter', () => {
  it('passes exports through while they succeed', async () => {
    const { inner, exportFn } = fakeExporter([]);
    const exporter = new CooldownExporter(inner, 60_000, () => 0);

    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.SUCCESS);
    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.SUCCESS);
    expect(exportFn).toHaveBeenCalledTimes(2);
  });

  it('drops exports for the cooldown after a failure, then tries again', async () => {
    let now = 1_000;
    const { inner, exportFn } = fakeExporter([{ code: ExportResultCode.FAILED, error: new Error('429') }]);
    const exporter = new CooldownExporter(inner, 60_000, () => now);

    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.FAILED);
    now += 59_999;
    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.FAILED);
    expect(exportFn).toHaveBeenCalledTimes(1);

    now += 1;
    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.SUCCESS);
    expect(exportFn).toHaveBeenCalledTimes(2);
  });

  it('turns an exporter that throws into a failed export and a cooldown', async () => {
    const { inner, exportFn } = fakeExporter([new Error('serializer blew up')]);
    const exporter = new CooldownExporter(inner, 60_000, () => 0);

    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.FAILED);
    expect((await exportOnce(exporter)).code).toBe(ExportResultCode.FAILED);
    expect(exportFn).toHaveBeenCalledTimes(1);
  });

  it('never rejects on shutdown', async () => {
    const inner: SpanExporter = {
      export: vi.fn(),
      shutdown: () => Promise.reject(new Error('nope')),
      forceFlush: () => Promise.reject(new Error('nope')),
    };
    const exporter = new CooldownExporter(inner);
    await expect(exporter.forceFlush()).resolves.toBeUndefined();
    await expect(exporter.shutdown()).resolves.toBeUndefined();
  });
});
