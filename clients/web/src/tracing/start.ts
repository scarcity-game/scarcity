import type { TracingConfig } from './config.ts';
import type { TracingHandle } from './sdk.ts';

export interface TracingSdk {
  initTracing(config: TracingConfig): TracingHandle | undefined;
}

/**
 * Loads the OpenTelemetry SDK chunk and starts tracing. Never throws and
 * never rejects: if the chunk fails to load or init throws, the app just runs
 * untraced. main.tsx calls this after React has been mounted, inside an
 * `if (TRACING_ENABLED)` so a SCARCITY_TRACING=off build has no SDK chunk.
 */
export async function startTracing(
  load: () => Promise<TracingSdk>,
  config: TracingConfig,
): Promise<TracingHandle | undefined> {
  try {
    const sdk = await load();
    return sdk.initTracing(config);
  } catch {
    return undefined;
  }
}
