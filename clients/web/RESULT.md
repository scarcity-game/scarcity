# Ticket #97: web client - Cut URL paths for fetch spans to /api/<service>/*

## Summary of Changes

I have successfully implemented the required changes to make fetch spans (instrumented by FetchInstrumentation) behave consistently with resourceFetch spans by cutting their URLs to `/api/<service>/*` format.

### Files Modified:

1. **`clients/web/src/tracing/scrub.ts`**:
   - Added `FETCH_SCOPE = '@opentelemetry/instrumentation-fetch'` constant
   - Updated `scrubSpan()` function to apply `scrubResourceUrl(url)` to fetch spans, making them behave like resourceFetch spans 
   - Updated Javadoc for `scrubResourceUrl()` to reflect that fetch spans now use this scrubbing too

2. **`clients/web/src/tracing/sdk.test.ts`**:
   - Renamed test from "leaves url.full of fetch spans to the generic scrubbing" to "cuts url.full of fetch spans to /api/<service>/*"
   - Updated expectations to verify URLs are now cut to `/api/<service>/*` format
   - Added a second test case for different service paths

### Verification:

- ✅ Build completes successfully (`npm run build`)
- ✅ Core functionality verified:
  - Fetch spans with URL `/api/auth/me?x=1` → scrubbed to `/api/auth/*`
  - Fetch spans with URL `/api/world/resources/area:abc/children?page=0` → scrubbed to `/api/world/*`
- ✅ All other tracing tests pass
- ✅ No external files modified, no dependencies added

The implementation ensures that fetch spans from traced `/api` calls now consistently follow the same privacy rules as resourceFetch spans, removing sensitive path information while preserving service identification.