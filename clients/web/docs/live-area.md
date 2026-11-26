# clients/web: live area, game time and area view details

Read when changing the games/area views, the resource cache, the WebSocket client or the game time readout. Moved verbatim from `AGENTS.md` (the summary there is the rule set; this is the detail).

## Games and the live area (task #31)
- **Routes** (under `RequireAuth`): `/games` (`GamesView`), `/games/:gameId`
  (`GameView`, redirects to the only area, or with several to the area holding the game's `player` (else `npc`) found by listing each area's entities, retrying every 2 s while it is between areas; the area view stays put when anything leaves its area, unless the spectator follows that entity: see "Following an entity"),
  `/games/:gameId/areas/:areaId` (`AreaView`). The path params are the bare
  uuids, without the type prefix (`/games/<uuid>/areas/<uuid>`).
  `src/resources/routeIds.ts` is the only place that converts: `gamePath` /
  `areaPath` build links, `segmentToId(type, segment)` turns a param back into
  `game:<uuid>`; an old-style segment that already has the right prefix is
  accepted, a wrong prefix gives `NotFoundView`. Resource ids stay prefixed
  everywhere else (cache, WebSocket) and are `encodeURIComponent`ed in REST URLs. `/play` is still the placeholder.
- **`src/resources/`**: `versionRule.ts` (pure `decideChange`), `versionChecksum.ts` (the
  version checksum, checked against the server's vectors), `cache.ts` (`ResourceStore`, immutable
  snapshots, `useResources`; stale marks), `worldApi.ts` (list/children/get/versions; paging with
  `size=200`; `If-None-Match` from the cached version, 304 keeps the cache), `sync.ts` (`AreaSync`,
  #186: loads the area, applies deltas, follows the logSeq chain, checks the checksum, resyncs and
  reloads; coalesces GETs per id; refetches unknown children after the area).
  - **Deltas** (`docs/resources.md`, "Change notifications"): an update (`set`, `unset`,
    `childrenAdded`, `childrenRemoved`) applies only to the cached `version - 1` (`applyUpdate`);
    holding less (or nothing) GETs the resource, holding as much or more ignores it. A stub is
    fetched. `created` is fetched if we don't hold it; the area's update already listed it in
    `childrenAdded`. `deleted` removes the resource and what is cached under it. `handleChanges`
    applies the whole batch inside `ResourceStore.batch`, which notifies subscribers once, so the
    view never shows a parent listing a child that is gone.
  - **The logSeq chain**: a batch whose `fromLogSeq` is past the last `logSeq` we followed means
    we missed one: apply what we can, then resync. A split batch's later parts repeat its
    `fromLogSeq`; the chain moves on after the last part.
  - **Checksum**: on subscribe and every ~30 s. Compared only after the initial load (an early one
    waits for it) and when no GET or resync is in flight (else the next one checks). A mismatch,
    or a `logSeq` past ours, resyncs: `GET …/{area}/versions`, then GET what differs or is
    missing and drop what isn't listed; its `logSeq` becomes the chain's head.
  - **Reload** (a crossing into the area, or a batch too big to send): drop the area and what is
    under it, load it again (`load()`: the area, then every page of its children).
- **`src/ws/subscriptionsClient.ts`**: framework-free; injectable WebSocket
  constructor and timers. Auth first, subscribe only after `ready`, ping every
  25 s, backoff 1 s doubling to 30 s (reset on `ready`), re-auth and
  re-subscribe on reconnect, close 4401 stops and calls `onUnauthorized`
  (`AuthProvider.rejectSession`). Protocol events at `console.debug` only.
- **Store is per area page**, created on mount and cleared on entering an area;
  unmount unsubscribes and stops the socket (so logout closes it).
- **`src/components/RoomSvg.tsx`**: viewBox in world units, y flipped
  (`svgY = -y`; entities with a `polygon` are drawn as `<polygon>`s under them, #297), no label (#79: the name is the mark's accessible name).
  Any entity with a `position` is drawn as its sprite (`src/sprites/`, #208), or
  a disc when its prototype has none; the view draws whatever the area currently lists.
- `apiRequest` has an `ifNoneMatch` option; a 304 resolves to `undefined`.
- **Tracing**: `/api/world` fetches already match `isTracedUrl`; WebSocket frames are not traced.
- **Env**: `VITE_WORLD_PROXY`, `VITE_SUBSCRIPTIONS_PROXY` (dev only, default
  `http://localhost:8080`); proxies sit before the generic `/api` one, `ws: true` for subscriptions.
- **Verified** with a mock auth/world/WebSocket server in a real browser: live
  movement, reconnect with backoff, 4401 redirect, unsubscribe on leave and logout.
- **Known limits**: games and areas lists show only the first page (50);
  a handshake rejection (HTTP 401 on upgrade) looks like 1006 and retries forever;
  a checksum that arrives while a GET is in flight is skipped, so a mismatch can wait ~30 s.

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
  shows the truth. The inspector, the hover card and the readout always show the truth.
- **Not animated**: the history the log store reads when the view opens, and anything before the first `changes`
  message with a `logSeq`. An advance split across two log pages plays as two fragments.
- **Deferred**: per-viewer speed and skip (#155); a log read that ends mid-advance.
- **Test note**: the first `AreaView` test used to fail about 1 run in 4 under the
  parallel full suite (1 s `findByRole` cold start); it now waits up to 4 s.

## Area screen layout and map view (#80 stage 1)
- The area view is an application screen (`src/layout/AreaScreen.tsx`): a grid with a slim header, the map, the inspector (side) and the log (footer). It fills the window under the app header and the page never scrolls; every region scrolls inside itself (`.area-region`, `.log-scroll`). Other routes still scroll inside `.app-main`.
- The header holds the name, the link back, the connection state, the game time and the replay start/controls (which wrap and scroll within 40% of the height when a replay is active), plus "Reset layout".
- Splitters sit between map and inspector and between map and log. The inspector width and log height are stored per browser in `localStorage` (`scarcity.areaLayout.v1`, `{"side":px,"log":px}`); unreadable, invalid or blocked storage gives the defaults (320 and 220 px). The grid caps them at 70% and 60% of the screen, so a saved size from a bigger window never hides the map.
- Below 720 px wide the regions stack (header, map, inspector, log) and there are no splitters or reset.
- The map's view is its `viewBox` (`useMapView`). Drag and arrow keys pan, wheel/pinch and the buttons zoom, Fit shows the whole room. The view is presentation only: marks, hover card positions (measured from the mark's rectangle at hover time), selection and Esc are unchanged. A drag is not a click. The map view is not saved.
- Not yet: moving, tabbing and collapsing panels (stage 2), pop-out windows (stage 3), touch pinch zoom.

## Following an entity (#265)
Following is off by default and per spectator: `useFollowing` (`src/views/useFollowing.ts`) holds the followed id in the area page state (browser only, lost on reload; not game state).
- **Control:** the Inspector shows a Follow / Stop following toggle (`aria-pressed`) for a selected entity.
- **Centring:** `RoomSvg` takes `followedId` and `onStopFollow`; `useMapView` centres the view on the entity drawn (animated) position as it moves. Followed from the fitted view, the zoom is `FOLLOW_ZOOM` (4x); the user zoom (wheel, `+`/`-`, buttons) is kept and keeps following. A manual pan (drag or arrow keys) or Fit stops following. An entity that is not in this room does not move the view.
- **Change of area:** when the followed entity disappears from the area it was seen in, `useFollowAcross` looks for the game area whose children hold its id (`findEntityArea`, every 2 s, up to 15 tries since it may be between areas) and navigates there (replace); following carries on. If it is not found, following stops and the spectator stays.
- **Stairs link (#283):** the "Go to <level>" link of a stairs' inspector navigates (a normal push) without touching the followed id. `useFollowAcross` only moves when the followed entity *leaves* the area it was seen in, and its "seen here" flag resets on every area change, so arriving in a level that doesn't hold the followed entity doesn't navigate back.
- **Not following:** nothing moves the spectator, whoever leaves, even when the area is left with no player or npc. `GameView` still lands on the area of the player (else npc).
