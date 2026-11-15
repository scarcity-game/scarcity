# clients/web: live area, game time and area view details

Read when changing the games/area views, the resource cache, the WebSocket client or the game time readout. Moved verbatim from `AGENTS.md` (the summary there is the rule set; this is the detail).

## Games and the live area (task #31)
- **Routes** (under `RequireAuth`): `/games` (`GamesView`), `/games/:gameId`
  (`GameView`, redirects when there is exactly one area),
  `/games/:gameId/areas/:areaId` (`AreaView`). The path params are the bare
  uuids, without the type prefix (`/games/<uuid>/areas/<uuid>`).
  `src/resources/routeIds.ts` is the only place that converts: `gamePath` /
  `areaPath` build links, `segmentToId(type, segment)` turns a param back into
  `game:<uuid>`; an old-style segment that already has the right prefix is
  accepted, a wrong prefix gives `NotFoundView`. Resource ids stay prefixed
  everywhere else (cache, WebSocket) and are `encodeURIComponent`ed in REST URLs. `/play` is still the placeholder.
- **`src/resources/`**: `versionRule.ts` (pure `decideChange`, `decideDigestEntry`),
  `cache.ts` (`ResourceStore`, immutable snapshots, `useResources`; a patch
  replaces `state`; stale marks), `worldApi.ts` (list/children/get; paging with
  `size=200`; `If-None-Match` from the cached version, 304 keeps the cache),
  `sync.ts` (`AreaSync`: handles `changes` and `digest`, coalesces GETs per id,
  refetches unknown children after the area).
  - **Created and destroyed resources** (`docs/resources.md`): a change for an
    id we do not hold, with a patch, is a *created* resource and is stored as
    it is (`children` or `[]`, no GET); without a patch it is fetched.
    `children` in a change replaces the cached child list (only when the change
    is applied; a stale change changes nothing). `{id, type, deleted: true}`
    removes the resource. `handleChanges` applies the whole batch inside
    `ResourceStore.batch`, which notifies subscribers once, so the view never
    shows a parent listing a child that is gone. `ResourceStore` has `remove`,
    `ids`, `batch`, and `applyPatch(id, version, patch, children?)`.
  - **`digest` is authoritative**: cached resources below the area (children,
    their children) that the digest does not list are dropped; listed unknown
    ids are fetched; the version rule applies to the rest. The area itself and
    other areas' resources are never dropped.
- **`src/ws/subscriptionsClient.ts`**: framework-free; injectable WebSocket
  constructor and timers. Auth first, subscribe only after `ready`, ping every
  25 s, backoff 1 s doubling to 30 s (reset on `ready`), re-auth and
  re-subscribe on reconnect, close 4401 stops and calls `onUnauthorized`
  (`AuthProvider.rejectSession`). Protocol events at `console.debug` only.
- **Store is per area page**, created on mount and cleared on entering an area;
  unmount unsubscribes and stops the socket (so logout closes it).
- **`src/components/RoomSvg.tsx`**: viewBox in world units, y flipped
  (`svgY = height - y`), fixed = square, mobile = circle, name label. An entity
  with a numeric `radius > 0` in its state is a circle of that radius. Any
  entity with a `position` is drawn (unknown kinds such as `pebble`/`rubble`
  get the square marker); the view draws whatever the area currently lists.
- `apiRequest` has an `ifNoneMatch` option; a 304 resolves to `undefined`.
- **Tracing**: `/api/world` fetches already match `isTracedUrl`; WebSocket frames are not traced.
- **Env**: `VITE_WORLD_PROXY`, `VITE_SUBSCRIPTIONS_PROXY` (dev only, default
  `http://localhost:8080`); proxies sit before the generic `/api` one, `ws: true` for subscriptions.
- **Verified** with a mock auth/world/WebSocket server in a real browser: live
  movement, reconnect with backoff, 4401 redirect, unsubscribe on leave and logout.
- **Known limits**: games and areas lists show only the first page (50);
  a handshake rejection (HTTP 401 on upgrade) looks like 1006 and retries forever;
  the initial load and the first digest can GET the same entity twice.

## Game time readout
- `src/game/clock.ts` `currentGameTime(clock, nowMillis)` implements the
  `docs/resources.md` "Game time" formula; null for a missing or invalid
  `area.state.clock`. `src/components/GameTimeReadout.tsx` re-reads the anchor
  from props every 100 ms (interval cleared on unmount), so a new anchor from an
  area change shows on the next refresh. Shown as `game time 1042.37 (rate 1.0)`
  or `game time —`.
- **Deferred**: the browser clock may differ from the server's, which shifts the
  readout (same class as the session-expiry limit); animating the action in
  progress is issue #62.
- **Test note**: the first `AreaView` test used to fail about 1 run in 4 under the
  parallel full suite (1 s `findByRole` cold start); it now waits up to 4 s.
