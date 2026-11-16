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
    is applied; a stale change changes nothing). `{id, resourceType, deleted: true}`
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
  (`svgY = height - y`), fixed = square, mobile, npc and mob = circle, name label. An entity
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

## Game time readout and animation
- `src/components/GameTimeReadout.tsx` shows `area.state.time` (`game time 1042.00`, `game time —` when it is not a
  number). No timer: it jumps with each update of the area and never steps through the times of the entries being
  animated. `src/game/clock.ts` (the anchor arithmetic) and the `area.state.clock` read are gone (areas have no clock
  since #112).
- The room animates what an advance did; the model is in `AGENTS.md` "Animation". Code: `src/game/animation.ts` (pure:
  beats, plans, interpolation), `src/game/animator.ts` (`AreaAnimator`: queue, hold, snap; wall time passed in),
  `src/game/useAreaAnimation.ts` (the hook: a 33 ms redraw while busy, which log entries are new).
- **Hold.** A `changes` message with a `logSeq` makes `AreaView` call `hold(store.getSnapshot())` *before* the batch
  is applied: the room keeps drawing the state before the advance until the log entries of that advance arrive (the
  log read is started by the same message), then plays them on top of it. If they do not arrive within 3 s the room
  shows the truth. The resources panel and the readout always show the truth.
- **Not animated**: the history the log store reads when the view opens, and anything before the first `changes`
  message with a `logSeq`. An advance split across two log pages plays as two fragments.
- **Deferred**: per-viewer speed and skip (#155); a log read that ends mid-advance.
- **Test note**: the first `AreaView` test used to fail about 1 run in 4 under the
  parallel full suite (1 s `findByRole` cold start); it now waits up to 4 s.
