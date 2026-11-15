# clients/web: area log panel and replay details

Read when changing `src/log/`, `src/replay/`, `LogPanel` or the Replay button. Contract: `docs/log.md` at the repo root. Moved verbatim from `AGENTS.md`.

## Area log panel (2026-11-13)
The area view shows the area's log (`docs/log.md`) in a panel beside the room (below it at narrow widths).
- `src/log/logStore.ts` `LogStore`: loads `GET /api/world/log?area=&after=&limit=500` (via `logApi.fetchLogPage` and
  `authRequest`), first asks for one entry (`limit=1`) to learn `first` and `last` and starts at `max(first - 1, last - max)`, so the first load is at most `max` entries; then pages until `last`. After that it follows `logSeq` (see below) or, without a live socket, polls every `pollMs` (default 1000; `AreaView` passes 5000), one request at a time, `setTimeout`. Holds at most
  the newest 2,000 entries (`MAX_ENTRIES`). If the server's `first` is greater than `lastSeq + 1` it drops what it
  holds and restarts from `first - 1`. A fetch error is kept in `getError()` and polling goes on. `start()` / `stop()` /
  `clear()`; `useLogEntries` and `useLogError` are the React hooks. The store knows nothing about the view, so the
  replay can reuse it (pass a different `FetchPage`).
- Following the log live (2026-11-13; `docs/log.md`): `ServerMessage.logSeq` reaches `onDigest(area, versions, logSeq?)` and `onChanges(area, changes, logSeq?)`; `AreaView` calls `logStore.notifyLogSeq(n)` for each and `logStore.setLive(status === 'live')` from `onStatus`. `notifyLogSeq(n)`: `n <= last seq held` does nothing; otherwise one read; during a read, a second read follows only if `n` is higher than any `n` that read was already asked for (repeats of the same `n` are not doubled). `setLive(true)` reads once and keeps the `pollMs` timer until the first `logSeq` arrives (an older `subscriptions` never sends one, so the panel does not freeze); after that no timer runs. `setLive(false)` resumes polling. Tests: `logStore.live.test.ts` (fake timers), `views/AreaView.log.test.tsx` (real timers, 3 s silence). Because the socket going live now reads the log, tests that count requests must filter `/api/world/log` out.
- `src/log/logTree.ts` `buildTrees(entries)`: pure; one tree per `root`, children under `cause` in `seq` order, an entry
  whose cause is not loaded sits at the top of its root's group. `routine` = only `orbit-step`/`wait` start and end
  entries. Rebuilt from the whole list on each change, so an `action-end` that arrives in a later poll lands under its start.
- `src/log/format.ts`: `drawText` (every key of a draw, bounds as `[min, max)`, floats with a decimal point, the
  categorical `value` as an index), `describe`, `entityOf` (resource, else effect target, else the first entity in
  `changes`, else effect source), `shortId`, `formatLogTime` (`toFixed(2)`, same as the game time readout).
- `src/log/LogPanel.tsx`: routine trees are collapsed to their first line by default, others expanded; every tree
  with more than one entry has a toggle. Follows the newest entry unless scrolled up (8 px slop). Hover or focus of
  an entry calls `onHighlight(entityId)`; the innermost entry handles the event (they nest and the events bubble).
  `AreaView` passes it to `RoomSvg` as `highlightId`, which draws a dashed ring (`.highlight-ring`).
- Names come from the resource cache, else from `changes[].state.name` seen in the log, else `type:…last4`.
- Not done: the log for `kind` filters and a "jump to newest" control. `LogPanel` also takes `currentSeq` (marked with `aria-current` and `.log-current`, scrolled into view, its tree expanded) and `onSelect(seq)` (click, or Enter/Space on the entry itself); the replay uses both.
- Tests: `format.test.ts`, `logStore.test.ts` and `logStore.live.test.ts` (fake timers), `logTree.test.ts`, `LogPanel.test.tsx` (a full throw
  tree), and the log test at the end of `views/AreaView.test.tsx`. Vitest's `testTimeout` is 20 s (cold start of the first test in a file).

## Area replay (2026-11-13; contract `docs/log.md`, "Playback, not re-simulation")
The area view has a **Replay** button. Replay mode replaces the live room (same `RoomSvg`, resource panel and log panel) with the area as the log says it was; live sync and the live `LogStore` keep running underneath and **Back to live** is instant (the replay is dropped, nothing is refetched). The client applies entries; it never computes rules.
- `src/replay/replay.ts`: pure `applyEntry(map, entry)` (ops `snapshot` clears the map and makes the area list every other snapshot resource as a child; `changed` replaces state and keeps the old version when `version` is null, ignores unknown ids; `created` adds under `parent`; `destroyed` removes the resource, its descendants and its place in the parent's child list) and `Replay(snapshot, entries)`: `seekIndex/seekSeq/seekTime` (forward is incremental, backward rebuilds from the snapshot; `seekTime(t)` applies every entry with `time <= t`, so same-time entries are all applied in `seq` order), `rebuildAt(i)` (pure, for tests), `resources` (a new Map instance only when the state changed), `effectRoots` / `prevEffectRoot` / `nextEffectRoot` (root `action-start` entries whose tree has an `effect` entry, i.e. a throw).
- `src/replay/loader.ts` `loadReplay(fetchPage, {signal, pageSize, onProgress})`: walks `kind=snapshot` pages (5 at a time) to the latest snapshot, then pages every entry after it up to the `last` the first page reported (the range is fixed; later entries are ignored). `no-snapshot` is a result, not an error. A page whose `first` is above the snapshot's `seq` means the log was pruned underneath: it restarts once, then throws `ReplayPrunedError`. Abort throws an `AbortError`. `logApi.fetchLogPage` got an optional `kind` argument.
- `src/replay/player.ts` `ReplayPlayer` (external store, `useReplayState` / `useOptionalReplayState`): a playback clock (`gameTime`) that advances from wall time at 1x, 4x or 16x on a 50 ms interval and stops at the end of the range; `seekTime` (scrubber), `seekSeq` (log click), `stepEntry(±1)`, `jumpRoot('prev'|'next')`; stepping and jumping pause. Between an `action-start` and its end the entity stays where its state says (no interpolation; that is #62). `now` and `intervalMs` are injectable for tests.
- `src/replay/useReplaySession.ts` (idle, loading with progress, error, active), `ReplayControls.tsx` (banner `role=status` with game time and entry seq, previous/next action, previous/next entry, play/pause, speed group, `Game time` range scrubber, Back to live; all native buttons, so keyboard reachable; they wrap at narrow widths).
- Fixture and tests: `src/replay/throwFixture.ts` (a full throw: pebble created seq 14 and destroyed 19, pillar `hp` 97.5 at 17, rubble 20 to 23), `replay.test.ts`, `loader.test.ts`, `player.test.ts`, `log/LogPanel.replay.test.tsx`, `views/AreaView.replay.test.tsx`.
- Known limits: a snapshot gives no child lists, so the area's children are taken to be all the other resources in it (true while areas hold only entities); the replay panel lists the whole range (at most what one area's log holds, no 2,000 cap); entries inside a collapsed routine tree are reached by clicking its toggle or its "(+N more)" button (the tree of the current entry opens itself); `Next entry` is disabled only at the end of the range.

## Log panel windowing (2026-11-13, issue #76)
- `LogPanel` builds `buildLogIndex(entries)` (`log/logIndex.ts`: trees, seq -> tree index, logged names) once per `entries` array and renders only a window of 60 trees (15 overscan) between two spacer divs sized by the measured average tree height. Live mode shows the newest window and sticks to the bottom; replay mode keeps the tree of `currentSeq` in the window and nudges the panel's own `scrollTop` (never `scrollIntoView`, which scrolled the page).
- `Replay.seekSeq/seekTime/prevEffectRoot/nextEffectRoot` use binary search. A backward seek still rebuilds from the snapshot (O(n)); playback only moves forward.
- Measured in jsdom, 12,000 entries: old panel first render 2706 ms, 782 ms per replay step, 12,000 entry elements; new 206 ms, 16.5 ms, 180 elements. The Back to live "2 s" was the slowness (each step and the remount of the live list took long); a test covers Back to live at 16x.
- Limit: one expanded tree with thousands of entries is not windowed inside the tree.

