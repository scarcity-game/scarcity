# clients/web: module agent notes

**Kind:** client · **Stack:** React + TypeScript + Vite, tests with Vitest + Testing
Library · **Served by:** nginx (unprivileged) on port 8080 · **k8s:**
`k8s-resources/client-web/` · **Image:** `ghcr.io/scarcity-game/scarcity-client-web`

## Purpose
The browser client. It renders game state and sends player intents. It never
decides game outcomes; the server does. It will be information dense: logs and
the exact distributions behind random outcomes.

## Deployment contract
- The production build is static files served by nginx on **8080**, as non-root
  (`nginxinc/nginx-unprivileged`, uid/gid 101).
- **Read-only root filesystem.** The image runs with `readOnlyRootFilesystem:
  true`, all capabilities dropped and no privilege escalation. The **only**
  writable path is `/tmp`, which must be mounted (an `emptyDir` in k8s,
  `--tmpfs /tmp` with docker). nginx keeps its pid and all five temp dirs
  there. Nothing else (not `/var/cache/nginx`, not `/etc/nginx`) is writable
  or needs to be. Logs go to stdout/stderr.
- `GET /health` returns `200 ok` (used for k8s probes).
- SPA fallback: unknown paths serve `index.html`.
- `/api/*` is routed by the Gateway to services, not to this container. In dev,
  Vite proxies `/api` to `http://localhost:8080` (the auth service run locally).
  Make the proxy target configurable through an env var.

Why the container is built this way: [`docs/nginx.md`](docs/nginx.md).

## Talking to services
- Auth API: see `services/auth/AGENTS.md`. Use same-origin relative URLs (`/api/auth/...`).
- Keep the JWT in memory, with `sessionStorage` as the fallback that survives a
  reload. Send it as `Authorization: Bearer <token>`.
- World API and WebSocket subscriptions: see "Games and the live area" below.

## Build/test
`npm ci && npm run lint && npm test && npm run build` (this is what CI runs;
`npm test` must be non-watch). `npm run typecheck` runs `tsc -b`.

## Lint (ESLint, flat config in `eslint.config.js`)
`npm run lint` runs ESLint with `typescript-eslint` (recommended), the classic
`react-hooks` rules (`rules-of-hooks`, `exhaustive-deps`) and complexity limits,
in the spirit of the Java lint ("extract till you drop": small functions, one
responsibility each).

| rule | limit |
|---|---|
| `max-lines-per-function` | 30 (blank lines and comments not counted) |
| `complexity` | 8 |
| `max-depth` | 3 |
| `max-params` | 4 |
| `max-statements` | 15 |
| `max-nested-callbacks` | 3 |

- Test files (`*.test.ts(x)`, `src/test/`) get a looser `max-lines-per-function`
  (400, because `describe` blocks are long by nature); every other limit applies
  to them too. A `describe` over 15 statements is split into nested `describe`s,
  a long `it` into helper functions.
- **No baseline and no `eslint-disable` for these rules.** Code over a limit is
  split (functions, components, hooks), not suppressed.
- **A limit is raised only with the human's OK**, never to get a change through.
- The React Compiler rules of `eslint-plugin-react-hooks` (`refs`, `immutability`,
  `set-state-in-effect`) are not enabled; the code reads and writes refs during
  render on purpose in a few places (`useLatest`, `useAreaAnimation`, `useLogScroll`).

## Formatting
Formatting follows `.prettierrc.json`. Only touched files are formatted.

## Layout of the code
- `src/main.tsx` mounts `<BrowserRouter><App/></BrowserRouter>`.
  `src/App.tsx` is `<AuthProvider><AppRoutes/></AuthProvider>`.
- `src/routes.tsx` holds the route table (`RouteObject[]` + `useRoutes`). There
  is one layout route (`src/layout/Layout.tsx`): a `<header>` with a brand link
  and the account nav, then `<main><Outlet/></main>`. Its children are:
  - the index (`HomeView`), `login`, `register`, and `*` (`NotFoundView`);
  - a `RequireAuth` route whose children are protected screens (currently
    the `play` placeholder). **Put game screens under `RequireAuth`.**
- `src/api/client.ts` has `apiRequest` (adds `Authorization: Bearer` when given
  a token), `ApiError` and `errorMessage()`. `src/api/auth.ts` has typed
  `register` / `login` / `me`.
- `src/auth/`:
  - `AuthProvider` + `useAuth()`;
  - `sessionStore` (sessionStorage);
  - `returnTo` (`safeReturnTo` for `?next=`);
  - `validation` (mirrors the auth credentials policy);
  - `RequireAuth` and `SessionCheck`.
  - `src/forms/Field.tsx` holds the accessible field and form-error components.
- In views, use `useAuth().authRequest(...)` for authenticated calls. It adds
  the token, checks expiry and ends the session on a 401.
- Tests sit next to code (`*.test.ts(x)`).
  - `src/test/renderAt.tsx` renders `<App/>` in a `MemoryRouter` at a given
    path, and `currentLocation()` reads the router's current location.
  - `src/test/mockApi.ts` stubs `fetch` with a route table and provides
    `json`/`problem` response builders.
  - `src/test/setup.ts` loads the jest-dom matchers and resets sessionStorage
    and stubbed globals around each test.
- Config: the Vitest config is inside `vite.config.ts`. `tsconfig.app.json`
  covers all of `src`, tests included. `tsconfig.node.json` covers
  `vite.config.ts`.

## Configuration
| env | where | meaning |
|---|---|---|
| `SCARCITY_API_TARGET` | `npm run dev` only | Where Vite proxies `/api` and `/api/...`. Defaults to `http://localhost:8080`. The dev server itself is on 5173 (`strictPort`). |
| `SCARCITY_TRACING` | build and dev (Docker ARG too) | `on` \| `off` \| `console`. Default `on` for `vite build`, `off` for `vite` dev and Vitest. `off` removes the OTel chunk from the output. `console` logs spans to the browser console. Empty counts as unset; other values fail the build. |
| `VITE_WORLD_PROXY` | `npm run dev` only | Proxy target for `/api/world`. Default `http://localhost:8080`. |
| `VITE_SUBSCRIPTIONS_PROXY` | `npm run dev` only | Proxy target for `/api/subscriptions` (WebSocket). Default `http://localhost:8080`. |
| `SCARCITY_OTLP_URL` | build and dev | Where spans are POSTed. Default `/otlp/v1/traces`. |
| `SCARCITY_OTLP_TARGET` | dev server and `vite preview` | Proxies `/otlp/...` to a local collector (e.g. `http://localhost:4318`), rewriting `/otlp/x` to `/x` like the Gateway. Unset: no proxy. |
| `GIT_SHA` | build (Docker ARG too) | `service.version` on spans, and in the name of every built file (`assets/<name>-<sha>-<content hash>.js`, `src/build/buildNames.ts`). Default: `git rev-parse --short HEAD`, else `unknown`. Must match `[0-9A-Za-z._-]{1,64}`. The Docker context has no `.git`, so CI must pass it as a build arg. |

## Games and the live area
Detail on the sync rules, components and known limits: [`docs/live-area.md`](docs/live-area.md) (read when changing resources, sync or the area view). Contracts: `docs/resources.md`, `docs/websocket.md`.
- **Landing and following** (`src/resources/followedArea.ts`, `GameView`, `AreaView`, `src/views/useFollowing.ts`): `GameView` lands on the area of the player (else npc) when the game has several areas; with exactly one it shows the area list holding it (all filter), not a redirect, so "Back to game" doesn't bounce back; in an area a spectator stays unless they chose Follow on an entity (#265), then the map centres on it and they change area with it. See `docs/live-area.md`.
- **Routes** (under `RequireAuth`): `/games`, `/games/:gameId`, `/games/:gameId/areas` (`AreaListView`, #257), `/games/:gameId/areas/:areaId`. Path params are the bare uuids; only `src/resources/routeIds.ts` converts to and from prefixed ids (`game:<uuid>`). Resource ids stay prefixed everywhere else and are `encodeURIComponent`ed in REST URLs. `/play` is still a placeholder.
- **`src/resources/`**: `versionRule.ts` (pure), `cache.ts` (`ResourceStore`, immutable snapshots, `useResources`), `worldApi.ts` (paging `size=200`, `If-None-Match`; a 304 resolves to `undefined`), `sync.ts` (`AreaSync`: loads the area, applies `changes` deltas, follows the logSeq chain, checks `checksum`, resyncs from `/versions` (more than 16 differing ids are loaded by the paged `children` listing, not one GET each), handles `reload`; coalesces GETs per id), `versionChecksum.ts`.
  - A `Resource` is flat JSON (`id`, `resourceKind`, `version`, `children`, plus its own keys). The socket never carries one (#186): an update applies `set`/`unset`/`childrenAdded`/`childrenRemoved` to the cached `version - 1`, else the resource is fetched; `created` and stubs are fetched; `deleted` removes. A batch is applied inside `ResourceStore.batch` (one notification).
  - A gap in the logSeq chain, a checksum mismatch or a checksum `logSeq` past ours resyncs from `GET …/{area}/versions`; a `reload` loads the area afresh. The checksum's test reads `libs/resource-model`'s vectors (listed in `modules.yaml` paths). Details: `docs/live-area.md`.
- **`src/ws/subscriptionsClient.ts`**: framework-free, injectable WebSocket and timers. Auth first, subscribe after `ready`, ping 25 s, backoff 1 s doubling to 30 s with each wait jittered into its upper half (`random`, injectable), close 4401 stops and calls `onUnauthorized`; any other close (a drain's 1012) reconnects. One socket per area: `AreaView` connects to `areaSocketUrl(areaId)` (`…/ws?area=<id>`, unencoded, the router's hash key; `docs/websocket.md`), and a new area is a new socket.
- **The store is per area page**; unmount unsubscribes and stops the socket (so logout closes it).
- **`src/components/RoomSvg.tsx`**: world-unit viewBox, y flipped; any entity with a `position` is drawn as a sprite (see "Sprites"), with no label (#79); an entity with a `polygon` (walls, #297) is drawn instead as an SVG `<polygon>` under the circles (a wall has none of its own: it names its `cell`, and the room draws that cell's polygon, #354; a wall whose cell is not loaded is not drawn, never a disc), filled with its material's pattern (see "Wall patterns"), keyed on the shape and never on a kind or prototype. y is flipped as `-y`; the view's fitted box is the bounding box of the loaded shapes (`shapes.ts`: circles `position` ± `radius`, polygons their vertices) plus 0.5 m (an area has no size; its walls are its edge, #298), and panning is limited to it. Marks are focusable buttons named by the entity; a click (or Enter/Space) selects, a click on the empty map clears.
- **Cells** (#354, `docs/resources.md` "Cells"): an area's `cell` children load with the rest into the cache, but are never marks: `AreaView` keeps the entities for `RoomSvg` and passes the cells as a map (`cells`; a replay whose log holds none falls back to the live ones). The map's "Floor cells" button (`aria-pressed`, off by default, absent when the area has no floor cell) draws every `floor` cell as a faint outline (`FloorCells.tsx`) under the walls; a click picks the cell (`data-cell-id`). The inspector shows a picked cell's kind, neighbours (the kind across each edge, "edge" where the tiling ends, the width of a floor-floor edge), `clearance` and `width`s (`inspector/cellFacts.ts`); a picked or hovered wall shows its cell's as `cell kind`, `cell neighbours`, `cell clearance`, `cell width`. An area's cells are left out of its child list.
- **Selection and the entity panel** (`src/inspector/`, #79): `useSelection` holds the selected id and the hovered mark per area page (Esc clears). `EntityPanel` is one component for the hover card (`HoverCard`, placed inside the map on the side with more room, `pointer-events: none`) and the docked `Inspector` (the selection, or the area with nothing selected; a button "Show area: <name>" (or "Show container: <name>") selects the parent, never worded as travel, and the children list goes down; stairs (`StairsInspector`, #283) show their direction and paired stairs, the name of the level they lead to in the `leads` fact, and a link "Go to <level>" (a react-router `Link` styled as a button, so it opens in a new tab too) to `games/:gameId/areas/:areaId`; the level is found client-side (`useStairsDestination`: `findEntityArea` over the game's areas, the one whose children hold the stairs' `to`) until the stairs carry `toArea` (#235), and a null `to` shows "No level there yet", a pending lookup a status and a failed one an alert; it is the side region of the area screen). `facts.ts` shows the server's values through `src/format/units.ts` (diameter = 2 × radius, or for a polygon entity its material, vertex count and bounding size); a missing value is "—", never estimated. A name in the log selects its entity.
- **The area screen** (`src/layout/`, #80 stage 1): `AreaScreen` is a CSS grid that fills the window under the app header and never scrolls as a page (`#root` is `100dvh`; `.app-main` holds `.area-screen` with no padding); each region scrolls inside itself. Regions: slim header (name, back link, connection, game time, replay controls, "Reset layout"), map (centre), inspector (side), log (footer). `Splitter` is our own small WAI-ARIA separator (drag, or arrow keys, 16 px a step); `layoutStore.ts` keeps `{side, log}` px in `localStorage` key `scarcity.areaLayout.v1` (reads and writes in try/catch, defaults for anything missing or invalid, min 120 px). Below 720 px (`useNarrow`, `matchMedia`) the regions **stack** (no tabs, no splitters, fixed 3:2:2 rows). **No docking library, no splitter library**: two splitters are about 70 lines, so a dependency was not clearly smaller; stage 2 (moving, tabbing, collapsing panels) will need a docking library (dockview or flexlayout-react) and should record its reason here.
- **Map pan and zoom** (`src/components/viewport.ts` pure, `useMapView.ts`): the SVG `viewBox` is the view. Drag pans (a pointer that moves under 4 px is a click; once it moves more, the pointer is captured and the click that ends the drag is swallowed in the capture phase, so it neither selects nor clears); arrow keys pan 10% of the view when the map (or a mark) has focus; wheel and trackpad pinch (ctrl+wheel) zoom about the cursor, `+`/`-` and the buttons zoom about the centre, `0` or the Fit button restores the whole room. Zoom 0.5x to 40x; the view's centre stays inside the room. Touch pinch is not handled (follow-up).
- **Tracing**: `/api/world` fetches are traced, WebSocket frames are not.
- **Game time**: `GameTimeReadout` shows `area.state.time` and jumps with each update (`docs/actions.md` "Game time"). No clock, no timer.
- **Animation**: see "Animation" below.
- **Area list** (`AreaListView`, #257): `GET resources?type=area&game=…&has=activeEntity` (filter **Active**, default, or **All** = `?show=all`) with `size=50`; "Load more" fetches the next page until `total`. Linked from `AreaView`'s header ("Areas"). `GameView` shows the same filter and rows (`AreaListPanel`, shared with `AreaListView`) while it looks for the followed area. No live updates: it reloads when the filter changes or the page opens.
- **New game** (#456, `docs/resources.md` "New games"): the Games list has a "New game" link to `/games/new` (`NewGameView`, `src/api/games.ts`, `newGameForm.ts`). The form is built from `GET /api/world/games/profiles` (the `arena` profile: one count input per mob, 0 to `maxDensPerMob`; nothing hard-coded); a count of 0 is left out of `dens`, and no den at all is refused before any request. A 400's `{"error"}` is shown on the form; success opens `gamePath(game)`. The spectator's turns are taken with the wait control (below).
- **Controls for your entity** (#518, `src/game/control/`): `ControlHud`, docked under the map (never over it), live only (not in a replay), for the entity of the area whose `controller` is `{kind:"user", sub}` of the signed-in user (`useOwnEntity`, `turn.ts`; the active one when several; nothing when none). `UnitFrame`: sprite, name, turn, and HP (damageable only), speed, step reach and vision from the entity (`stepReach`, `vision`, docs/resources.md), with "Centre" (`aria-pressed` while followed). `ActionBar` (`role="toolbar"`): one control per type in the entity's `actions`, in its order, labelled by `actionLabels.ts`; `wait` is the wait control below, a type with no input yet is a disabled button. Then "Inventory" and "Equipment" (`aria-expanded`), one `ItemsPanel` open at a time: the entity's `item` children the view holds, and no equipment until the rules have slots (#520). **Camera:** the page follows your entity the first time it sees it (a new id counts again), and following frames the followed entity's `vision` as a square of twice it (`MapFollow.radius`), so the shorter side of the map shows that radius; a pan stops it as any following, Centre resumes.
- **Move mode** (#519, `useMoveMode.ts`, `MoveAction.tsx`, `stepPreview.ts`, `src/components/MoveOverlay.tsx`): the action bar's `step` is "Move", a toggle (`aria-pressed`); Move again or Esc leaves it, a step doesn't. In move mode on the entity's turn (`MoveMode.overlay`, passed to `RoomSvg` as `move`) the map draws the `stepReach` circle round the entity and, for the point under the pointer, a line to a ghost of the entity; the action bar tells its distance and time (distance / `speed`, `formatRoughQuantity`). A target beyond the reach is pulled back to its edge on the same line. A click on the empty map posts `step` (`postStep`, `{to}` in world units, on the entity version) instead of clearing the selection; a click on a mark still selects, a drag still pans. The arrow keys step 1 m (up is north) wherever focus is but a form field, and the map's own arrows don't pan then (`useMapView(…, arrowKeys)`); out of move mode, or off turn, they pan as before. One post at a time, and the next waits for the view to hold the version the last answer gave. A refusal shows for 5 s as wait's does. The preview doesn't predict walls: the server cuts the step short.
- **Wait control** (#482, `src/game/WaitControl.tsx` `WaitAction`, `useWait.ts`, `src/api/intents.ts`): in the action bar, for the entity of the area whose `controller` is `{kind:"user", sub}` of the signed-in user (the active one when several; nothing when none). Its turn is `area.activeEntity.entity === entity.id`, read from the cached area, so the button follows the subscription live (the `turn` frame is not used). "Wait" posts `POST /api/world/intents` through `authRequest` with `{entity, actionSpec:{type:"wait",params:{duration}}, ifVersion: <entity version>}`; duration 1, 5 (default) or 15 s. Disabled when it is not the turn and while a post is in flight. A refusal (409 stale/busy/not your turn, 422, network) shows the server's `{"error"}` for 5 s as an alert; the subscription catches up. "Auto" (off by default) posts again whenever it is the turn and the view holds the entity version of the last answer (so it never posts a stale `ifVersion`), and turns itself off at any error.
- **Known limits**: `GameView`'s own list shows only the first page (50); an upgrade rejection (HTTP 401) looks like 1006 and retries forever; a checksum that arrives while a GET is in flight is skipped (the next one checks).

## Numbers and units
**Every number the client shows goes through `src/format/units.ts`; never `toFixed`, `toPrecision` or a template string with a unit elsewhere.** `formatNumber` keeps 4 significant figures with no padding (`0.5`, `1235`, `0.0001235`). `formatQuantity(value, 'mass' | 'length' | 'time' | 'speed')` scales the server's base unit (g, m, s, m/s) to the unit that fits the rounded amount: mg g kg Mg (tonne) Gg; mm cm m km; mm/s m/s km/s. **Time** (`formatTime`) is not decimal: `250 ms` under a second, `12.35 s` under a minute, then a clock in whole seconds (`35:49`, `15:07:12`), with days (`3d 15:07:12`) and years of 365 days (`2y 3d 15:07:12`); no `~`, the tooltip has the exact seconds. `formatPoint` puts both coordinates of a position in one length unit. `formatRoughQuantity` cuts to 2 significant figures, only for a number that follows the pointer (the move preview). `compactJson` cuts every number in a JSON text. The exact value is never lost: each display site puts `exactNumber` / `exactQuantity` / `exactPoint` / `exactJson` in a `title` (fact values, log time, draws, a line's result, the game-time readout, the area list row).

## Sprites
Code: `src/sprites/`; art and manifest: `content/sprites` (its `AGENTS.md`). The client imports `sprites.json` and the SVG sheets at build time (`manifest.ts`, Vite `?raw`), so the Docker build context is the **repo root** and `vite.config.ts` allows reading `../..` (`server.fs.allow`). `SpriteSheets` inlines the sheets once, hidden, into the area page; every sprite is one `<use href="#<symbol>">` (plus one per overlay, moved to the frame's anchor when the overlay names one: `overlayPlacement`) in a `<g>` carrying the appearance's colours as one `--sprite-<slot>` CSS variable per paint slot (`coloursStyle`). An entity an effect acts upon also gets the effect's icon (`effects.svg`, by `effects.types`, default `physical`) in the effect kind's own colours, at the frame's `impact` anchor (`effectIcon`, `EffectUse`), whatever its state: a pillar shows it too.
- **Which sprite** (`choice.ts`, `appearanceOf`, `lookOf`): the appearance of `entity.prototype` in the manifest's `appearances`. No appearance: a disc in the category's `fallbackColor` when the prototype names a category (`prototype: "humanoid"`), else a neutral disc (`#8a8a8a`); an unknown category is neutral too. Each gap is `console.warn`ed once per reason (`gaps.ts`). `placeholder` is a neutral disc without a warning.
- **Size:** the sprite's circle (radius 16 of the 32 grid) is the entity's `radius`, never under `MIN_RADIUS` = 0.15 m, so a pebble (0.015 m) and a mouse (0.04 m) stay visible. Rings and the hit disc use the drawn radius.
- **Which state** (`stateOf`): `acted-upon` when an effect entry of the beat on screen (live: `Frame.actedUpon`; replay: the entry the replay stands on) has the entity as `resource` or in its `actedUpon` (`actedUpon` maps the id to that effect's `loggedEffect.type`); else from `actionInProgress.type`: `step` and `fly` are `moving`, `wait`, `decay`, `arrive` and none are `idle`, any other (`throw`, `traverse`) is `acting`. A category without the state shows `idle`, else `static`.
- **Frame clock** (`clock.ts`, `SpriteClock`): wall time times `animationSpeed` times the replay speed (capped at 4), running live and while a replay plays, still when it is paused. Each sprite subscribes through `useSyncExternalStore` to its own frame number, so only a sprite whose frame changed re-renders; an id-derived phase keeps entities of one category out of step. Static sprites do not subscribe; the clock ticks (40 ms) only while something listens.
- **Entity panel and hover card:** `SpriteIcon` (40 px, idle frame 0) beside the name; the area has none.
- Measured (headless Chromium, 1440x900, software rendering): 600 moving entities hold 60 fps; see the PR for numbers.

## Wall patterns
Code: `src/components/materials.ts`, `RoomSvg` (`PatternDefs`), `src/views/useMaterialPatterns.ts` (#332). A wall's pattern is data, never a branch on a material name: `game.materialPatterns[material]` (the area page reads its game once), else `sprites.json` `materials[material]`, else a flat `NEUTRAL_FILL`; a name that is not a key of `sprites.json` `patterns` counts as missing. The room defines one SVG `<pattern>` per pattern in use (`userSpaceOnUse`, `metres` wide, origin at the world's origin, so the texture scales with zoom and lines up across walls) holding the `fallbackColor` and a `<use>` of `#pattern.<name>`; the symbols are bundled with `import.meta.glob` over `content/sprites/patterns/*.svg` into `sheetSources` and inlined by `SpriteSheets`. A game that can't be read just means the manifest defaults. `bedrock` (the marker material of an area's outline walls) is never in a game's `materialPatterns`, so it always takes the manifest's `bedrock` pattern; `screenshots/fixtures.ts` has a bedrock wall (`BEDROCK_WALL`) for the `bedrock-wall-selected` scene.

## Animation
Detail: [`docs/live-area.md`](docs/live-area.md). Code: `src/game/`. It is presentation only: the server's state is the truth and the animation shows what already happened.
- **Not tied to wall time or to game-time durations.** An advance (the entries from one `intent` to the next) plays as **beats**: entries at the same game time, in log order, are one beat. Each beat takes one `BEAT_MS` (250 ms) whatever its game-time length, so a `wait 1.0` and a short `step` get the same beat. `BEAT_MS` is divided by one `animationSpeed` value (`src/game/animation.ts`), the single knob a per-viewer preference can scale later (#155).
- **What a beat shows.** The state before it stays up until its end (so what is destroyed in it is still there); what is created in it is there from its start; what moves is on its segment: the end of a `step` from where the entity was to `params.to`, or to `result.stoppedAt` when `result.reached` is false; the end of a `fly` to `params.to`; an `action-interrupted` (a beat of its own, at the interruption's game time) to the position in its state, i.e. where the mover was stopped, never on to `params.to`; any other changed `position` to the position in the entry's state. At 1 the beat is the state after its entries.
- **The readout does not animate.** `GameTimeReadout` shows `area.state.time` and jumps with every update; the inspector and the hover card show the truth too. Only the room (`RoomSvg`) shows the animated state.
- **Backlog.** An advance arriving mid-animation is queued. If more than one waits, the older ones snap to their end state: skipped, not sped up. The current one is never cut short. When the queue is empty the room shows the truth again.
- **Hold.** A `changes` message with `logSeq` makes the room keep the state before the advance until its log entries arrive (3 s at most), then play them on top of it. History read when the view opens, and anything before the first such message, is not animated.
- **Wall time is injected.** `AreaAnimator` and the pure helpers never read a clock: `now` is a parameter (`useAreaAnimation` supplies `Date.now`), so tests drive time. Replay has its own playback clock and does not use the animator.
- **Log lines.** The panel and replay show `intent` entries (`orbital chose throw (reported: p = 0.25, u = 0.61)`, the `reported.draw` in full and marked `reported:`); `clock-anchored` is no longer expected. `action-interrupted` reads `interrupted step at (x, y) by the damage effect` or `by contact with <name>` (or the area's boundary), the cause found through the entry's `cause` (`contact` entries are logged but not animated); an `effect`'s `actedUpon` is listed under it (`in recovery, not interrupted` for `interrupted: false`) and makes those sprites `acted-upon`. Replay's "next action" jumps to the `intent` whose `root` has effects (the data keeps `cause`/`root`; only the panel is flat).

## Area log panel and replay
Detail (log store paging, live following, tree/format rules, replay loader/player, windowing and measurements): [`docs/log-and-replay.md`](docs/log-and-replay.md). Read it when touching `src/log/` or `src/replay/`. Contract: `docs/log.md`.
- `LogStore` (`src/log/logStore.ts`) loads `GET /api/world/log`, keeps at most 2,000 entries (`MAX_ENTRIES`), follows `logSeq` from the socket (`notifyLogSeq`, `setLive`) and polls only without a live socket. It knows nothing about the view.
- Because a live socket makes the log store read `/api/world/log`, tests that count requests must filter that path out.
- `LogPanel` renders a window of flat rows, one per entry in seq order (`logIndex.ts`); a cause from an earlier time is a link on the row, never a nesting. Hover or focus on an entry calls `onHighlight(entityId)` (the room draws a ring).
- **Replay** (`src/replay/`) applies log entries to a snapshot; the client never computes rules. Live sync keeps running underneath, so Back to live is instant. The panel and room are shared with live mode.
- Not done: `kind` filters and a "jump to newest" control.
- Vitest `testTimeout` is 20 s (cold start of the first test in a file). Testing Library's `asyncUtilTimeout` is 10 s, set in `src/test/setup.ts`: its 1 s default is shorter than the first render in a file on a loaded machine, which flaked `findBy*` in different tests (#269). Don't add per-call `{ timeout }` overrides. To reproduce load flakes, run the suite beside a few dozen `yes > /dev/null` CPU burners.

## Screenshots
A PR that changes what the UI shows carries screenshots in its description
(root `AGENTS.md`). `npm run screenshots` takes them in a real browser, against
a **mocked** backend, so no world, auth or Postgres is needed.

```bash
npm ci
npm run screenshots:install   # once: downloads Chromium for Playwright
npm run screenshots           # every scene, wide and narrow
npm run screenshots -- --only hover,replay --full-page
```

- `screenshots/run.ts` starts the Vite dev server (port 5199 or the next free
  one) and drives Chromium (`@playwright/test`, pinned like
  `clients/web-acceptance-tests`). It is not part of `npm test` or CI. Options:
  `--only a,b` (scene names), `--out dir` (default `screenshots/out/`, git
  ignored), `--full-page` (the whole page, not the viewport), `--app dir`
  (serve another checkout of `clients/web`, e.g. a PR branch in a git
  worktree with `node_modules` linked; the fixtures and scenes still come from
  here).
- Each scene is written as `<scene>-wide.png` (1440x900) and
  `<scene>-narrow.png` (390x844).
- `screenshots/mock.ts` answers `/api/*` by route interception, the
  subscriptions socket by `routeWebSocket` (`auth` gets `ready`, `subscribe`
  gets `subscribed`) and puts a session in `sessionStorage`. A request it
  cannot answer gets a 404 and is printed as `unmocked:`; add it to `reply()`.
- `screenshots/fixtures.ts` is the world: one game, one area ("Test room")
  with an orbiter, a mouse, a pillar, rubble and stairs, and a log built from
  `src/replay/throwFixture.ts`.
- **A new scene** is one entry in `screenshots/scenes.ts`: a name and a `show`
  function that drives the already open area view into the state to
  photograph. For a new screen, change `AREA_PATH` or give a scene its own path.
- Look at every PNG (blank, an error page or a cut-off view means retake) and
  take the "before" from `origin/main` (a checkout without your change).

Publishing: images live on the orphan branch `pr-assets`, under `<pr number>/`,
never on the PR's branch. Use a worktree so your own checkout is untouched:

```bash
git fetch origin pr-assets
git worktree add ../pr-assets-wt origin/pr-assets --detach
mkdir -p ../pr-assets-wt/<pr>
cp clients/web/screenshots/out/*.png ../pr-assets-wt/<pr>/
cd ../pr-assets-wt
git add <pr>
git commit -m "screenshots for #<pr>"
git push origin HEAD:pr-assets
cd - && git worktree remove ../pr-assets-wt
```

Link each image in the PR description by its raw URL:
`![hover, wide](https://github.com/scarcity-game/scarcity/raw/pr-assets/<pr>/hover-wide.png)`.
Use distinct names for a "before" (`before-area-wide.png`). If a review
changes the UI, push new images with new names and update the description.

## Further docs (read only when the task needs them)
- [`docs/nginx.md`](docs/nginx.md): Dockerfile, `nginx.conf`, `nginx-main.conf`, read-only root filesystem and `/tmp`, entrypoint scripts, deployment needs, measurements. Read when changing the image or the k8s securityContext.
- [`docs/tracing.md`](docs/tracing.md): browser OpenTelemetry internals (SDK line and pins, exporter, what is traced, privacy scrubbing, context, lazy-load trade-off, tests, known limits). Read when changing `src/tracing/` or OTel dependencies.
- [`docs/decisions.md`](docs/decisions.md): why the stack, router, build, dev proxy, auth state, restore, expiry, return-to, errors, validation and logout are as they are. Read before changing one.
- [`docs/live-area.md`](docs/live-area.md) and [`docs/log-and-replay.md`](docs/log-and-replay.md): see above.

## Gotchas (full reasons in `docs/`)
- The image builds from the **repo root** (`context: .` in `modules.yaml`, `docker build -f clients/web/Dockerfile .`), because the bundle includes `content/sprites`; `.dockerignore` is the root one.
- Keep the literal `__SCARCITY_TRACING__ !== 'off'` in `main.tsx`; through a re-exported constant the OTel chunk is still emitted. Traced requests reach test stubs with absolute URLs (`${origin}/api/...`); stub fetch, init tracing, shut down in `afterEach`.
- Only `/api` and `/api/...` (whole segment) are traced; no username, user id, body or header may reach a span (`ScrubbingSpanProcessor` allowlist).
- Any future nginx `*_cache_path` must go under `/tmp`; never add a writable path elsewhere.
- Each view owns exactly one `<h1>`; the header brand is a link.
- `npm run build` is `tsc -b && vite build`, so type errors in tests fail CI. Versions are pinned exactly; jsdom stays on 29.x (Node 24.2 machines), TypeScript on 5.x. `.nvmrc` is `24`.
- An empty `SCARCITY_API_TARGET=` breaks the proxy; unset it instead.
- Network errors and 5xx on session restore keep the session; only a 401 for the current token ends it. `safeReturnTo` allows only a same-origin path with one leading `/`.
- Deployment needs outside this module: the Deployment must set the securityContext and mount an `emptyDir` at `/tmp`; CI passes `GIT_SHA` as a build arg; the acceptance stack builds with `SCARCITY_TRACING=off`.
