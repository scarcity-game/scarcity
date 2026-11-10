// Build-time tracing settings (see vite.config.ts for the env vars).
//
// To switch code off in a SCARCITY_TRACING=off build, test
// `__SCARCITY_TRACING__ !== 'off'` directly at the use site (as main.tsx
// does). The bundler only drops a dynamic import() when the condition is a
// literal in the same module; a constant re-exported from here is not folded
// early enough, and the SDK chunk would still be emitted.

export interface TracingConfig {
  /** `on` exports OTLP/HTTP to `otlpUrl`; `console` logs spans instead. */
  mode: 'on' | 'console';
  /** OTLP/HTTP traces endpoint, absolute or relative to the page. */
  otlpUrl: string;
  /** `service.version`: the git sha the bundle was built from. */
  serviceVersion: string;
}

export function buildTracingConfig(): TracingConfig {
  return {
    mode: __SCARCITY_TRACING__ === 'console' ? 'console' : 'on',
    otlpUrl: __SCARCITY_OTLP_URL__,
    serviceVersion: __SCARCITY_VERSION__,
  };
}
