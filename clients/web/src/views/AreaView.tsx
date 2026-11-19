import { useEffect, useRef, useState, type MutableRefObject, type RefObject } from 'react';
import { AreaScreen } from '../layout/AreaScreen.tsx';
import { HoverCard } from '../inspector/HoverCard.tsx';
import { Inspector } from '../inspector/Inspector.tsx';
import { useSelection, type Selection } from '../inspector/useSelection.ts';
import { Link, useNavigate, useParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import { GameTimeReadout } from '../components/GameTimeReadout.tsx';
import { animationSpeed } from '../game/animation.ts';
import { SpriteClockContext, useOwnedSpriteClock } from '../sprites/clock.ts';
import { SpriteSheets } from '../sprites/Sprite.tsx';
import { useAreaAnimation, type AreaAnimation } from '../game/useAreaAnimation.ts';
import { RoomSvg } from '../components/RoomSvg.tsx';
import type { LogEntry } from '../log/types.ts';
import { LogPanel } from '../log/LogPanel.tsx';
import { fetchLogPage } from '../log/logApi.ts';
import { LogStore, useLogEntries, useLogError } from '../log/logStore.ts';
import { ResourceStore, useResources, type Resource } from '../resources/cache.ts';
import { followedEntity } from '../resources/followedArea.ts';
import { gamePath, segmentToId } from '../resources/routeIds.ts';
import { AreaSync } from '../resources/sync.ts';
import { getResource, listAllChildren, type Request } from '../resources/worldApi.ts';
import {
  SubscriptionsClient,
  defaultSocketUrl,
  type ConnectionStatus,
  type SubscriptionsHandlers,
} from '../ws/subscriptionsClient.ts';
import { resourceName } from './GamesView.tsx';
import { NotFoundView } from './NotFoundView.tsx';
import { ReplayControls } from '../replay/ReplayControls.tsx';
import { useOptionalReplayState, type PlayerState, type ReplayPlayer } from '../replay/player.ts';
import {
  useReplaySession,
  type ReplaySession,
  type ReplayStatus,
} from '../replay/useReplaySession.ts';

const STATUS_LABEL: Record<ConnectionStatus, string> = {
  connecting: 'Connecting…',
  live: 'Live',
  reconnecting: 'Reconnecting…',
  closed: 'Disconnected',
};

export function AreaView() {
  const params = useParams();
  const gameId = segmentToId('game', params.gameId);
  const areaId = segmentToId('area', params.areaId);
  if (gameId === null || areaId === null) return <NotFoundView />;
  return <AreaLoaded gameId={gameId} areaId={areaId} />;
}

/** A ref that always holds the value of the latest render. */
function useLatest<T>(value: T): MutableRefObject<T> {
  const ref = useRef(value);
  ref.current = value;
  return ref;
}

interface AreaConnection {
  status: ConnectionStatus;
  lastError: string | null;
  loadError: string | null;
}

/** Values the socket's handlers read as of the latest render. */
interface Latest {
  token: string | null;
  animation: AreaAnimation;
  rejectSession: (reason: 'unauthorized' | 'expired') => void;
}

interface SocketContext {
  areaId: string;
  sync: AreaSync;
  store: ResourceStore;
  logStore: LogStore;
  latest: { current: Latest };
  setStatus: (status: ConnectionStatus) => void;
  setLastError: (message: string | null) => void;
}

function socketHandlers(c: SocketContext): SubscriptionsHandlers {
  return {
    onStatus: (s, err) => {
      c.logStore.setLive(s === 'live');
      c.setStatus(s);
      c.setLastError(err);
    },
    onDigest: (area, versions, logSeq) => {
      if (area !== c.areaId) return;
      c.sync.handleDigest(versions);
      if (logSeq !== undefined) c.logStore.notifyLogSeq(logSeq);
    },
    onChanges: (area, changes, logSeq) => {
      if (area !== c.areaId) return;
      // Keep the state before this advance up until its log entries arrive (they are animated).
      if (logSeq !== undefined) c.latest.current.animation.hold(c.store.getSnapshot());
      c.sync.handleChanges(changes);
      if (logSeq !== undefined) c.logStore.notifyLogSeq(logSeq);
    },
    onError: (message) => c.setLastError(message),
    onUnauthorized: () => c.latest.current.rejectSession('unauthorized'),
  };
}

// Initial load: the area, then all pages of its children.
async function loadInitial(areaId: string, store: ResourceStore, io: Io): Promise<void> {
  const { authRequest, signal } = io;
  const area = await getResource(authRequest, areaId, { signal });
  if (area) store.put(area);
  const children = await listAllChildren(authRequest, areaId, signal);
  for (const child of children) store.put(child);
}

/** What requests of the connection use: the session's `authRequest`, and the signal that ends them. */
interface Io {
  authRequest: Request;
  signal: AbortSignal;
}

function createSync(
  areaId: string,
  store: ResourceStore,
  io: Io,
  setLoadError: (message: string) => void,
): AreaSync {
  const { authRequest, signal } = io;
  const fetchResource = (id: string, cachedVersion?: number) =>
    getResource(authRequest, id, { cachedVersion, signal });
  return new AreaSync(areaId, store, fetchResource, setLoadError);
}

function reportLoadError(load: Promise<void>, setLoadError: (message: string) => void): void {
  load.catch((err) => {
    if (!isAbortError(err)) setLoadError(errorMessage(err));
  });
}

function useSocketState() {
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  const [lastError, setLastError] = useState<string | null>(null);
  return { status, lastError, setStatus, setLastError };
}

interface AreaSources {
  areaId: string;
  store: ResourceStore;
  logStore: LogStore;
  animation: AreaAnimation;
}

/** Loads the area into `store`, and keeps it and `logStore` current over the subscriptions socket. */
function useAreaConnection({ areaId, store, logStore, animation }: AreaSources): AreaConnection {
  const { authRequest, token, rejectSession } = useAuth();
  const latest = useLatest({ token, animation, rejectSession });
  const { status, lastError, setStatus, setLastError } = useSocketState();
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    store.clear();
    setLoadError(null);
    const controller = new AbortController();
    const io = { authRequest, signal: controller.signal };
    const sync = createSync(areaId, store, io, setLoadError);
    const client = new SubscriptionsClient({
      url: defaultSocketUrl(),
      getToken: () => latest.current.token,
      handlers: socketHandlers({ areaId, sync, store, logStore, latest, setStatus, setLastError }),
    });

    reportLoadError(loadInitial(areaId, store, io), setLoadError);

    client.subscribe(areaId);
    client.start();
    return () => {
      controller.abort();
      sync.close();
      client.unsubscribe(areaId);
      client.stop();
    };
  }, [authRequest, areaId, store, logStore, latest, setStatus, setLastError]);

  return { status, lastError, loadError };
}

// A spectator follows the game's player (or npc): when it leaves this area (stairs), the
// game page finds the area it is in now.
function useFollowedNavigation(
  resources: ReadonlyMap<string, Resource>,
  areaId: string,
  gameId: string,
) {
  const navigate = useNavigate();
  const followedId = useRef<string | null>(null);
  useEffect(() => {
    followedId.current = null;
  }, [areaId]);
  useEffect(() => {
    const present = (resources.get(areaId)?.children ?? [])
      .map((id) => resources.get(id))
      .filter((r): r is Resource => r !== undefined);
    const now = followedEntity(present);
    if (now) followedId.current = now.id;
    else if (followedId.current !== null) {
      followedId.current = null;
      navigate(gamePath(gameId), { replace: true });
    }
  }, [resources, areaId, gameId, navigate]);
}

/** The log store of the area; the log is read only while the view is open. */
function useAreaLogStore(authRequest: Request, areaId: string): LogStore {
  const authRequestRef = useLatest(authRequest);
  const areaIdRef = useLatest(areaId);
  const [logStore] = useState(
    () =>
      new LogStore(
        (after, limit, signal) =>
          fetchLogPage(authRequestRef.current, areaIdRef.current, { after, limit }, signal),
        { pollMs: 5000 },
      ),
  );
  // It follows logSeq while the socket is live and polls otherwise.
  useEffect(() => {
    logStore.clear();
    logStore.start();
    return () => logStore.stop();
  }, [logStore, areaId]);
  return logStore;
}

// The log entries that arrived are animated on top of the state before them.
function useAnimateNewEntries(
  logEntries: readonly LogEntry[],
  store: ResourceStore,
  animation: AreaAnimation,
) {
  const animationRef = useLatest(animation);
  useEffect(() => {
    animationRef.current.follow(logEntries, store.getSnapshot());
  }, [logEntries, store, animationRef]);
}

interface AreaReplay {
  session: ReplaySession;
  player: ReplayPlayer | null;
  state: PlayerState | null;
}

function useAreaReplay(authRequest: Request, areaId: string): AreaReplay {
  const authRequestRef = useLatest(authRequest);
  const areaIdRef = useLatest(areaId);
  const session = useReplaySession((after, limit, kind, signal) =>
    fetchLogPage(authRequestRef.current, areaIdRef.current, { after, limit, kind }, signal),
  );
  const player = session.status.phase === 'active' ? session.status.player : null;
  const state = useOptionalReplayState(player);
  return { session, player, state };
}

function entitiesOf(ids: readonly string[] | undefined, from: ReadonlyMap<string, Resource>) {
  return (ids ?? []).map((id) => from.get(id)).filter((r): r is Resource => r !== undefined);
}

function shownEntities(
  areaId: string,
  area: Resource | undefined,
  shown: ReadonlyMap<string, Resource>,
) {
  return entitiesOf(shown.get(areaId)?.children ?? area?.children, shown);
}

function numberOf(value: unknown): number | null {
  return typeof value === 'number' ? value : null;
}

// In a replay, the target of the effect entry the replay stands on is being acted upon.
function replayActedUpon({ player, state }: AreaReplay): ReadonlySet<string> | undefined {
  const entry = player === null || state === null ? undefined : player.replay.entries[state.index];
  return entry?.kind === 'effect' && entry.resource !== null
    ? new Set([entry.resource])
    : undefined;
}

/** What is drawn: the room shows the animated `shown`; the panels and readout show `view`. */
function areaScene(
  areaId: string,
  resources: ReadonlyMap<string, Resource>,
  replay: AreaReplay,
  animation: AreaAnimation,
) {
  // `view` is the truth (or the replay)
  const view: ReadonlyMap<string, Resource> = replay.state ? replay.state.resources : resources;
  const area = view.get(areaId);
  const frame = replay.state
    ? { resources: view, acting: undefined, actedUpon: replayActedUpon(replay) }
    : animation.frame(resources);
  return {
    view,
    area,
    acting: frame.acting,
    actedUpon: frame.actedUpon,
    entities: shownEntities(areaId, area, frame.resources),
    width: numberOf(area?.width),
    height: numberOf(area?.height),
  };
}

function AreaHeader({
  gameId,
  area,
  connection,
}: {
  gameId: string;
  area: Resource | undefined;
  connection: AreaConnection;
}) {
  const { status, lastError } = connection;
  return (
    <>
      <h1>{area ? resourceName(area) : 'Area'}</h1>
      <p>
        <Link to={gamePath(gameId)}>Back to game</Link>
      </p>
      <p className="connection" data-status={status} role="status">
        Connection: {STATUS_LABEL[status]}
        {lastError && <span className="form-error"> Last error: {lastError}</span>}
      </p>
    </>
  );
}

function ReplayLoading({
  status,
  onCancel,
}: {
  status: ReplayLoadingStatus;
  onCancel: () => void;
}) {
  return (
    <>
      <progress aria-label="Loading replay" max={Math.max(status.total, 1)} value={status.loaded} />{' '}
      <span role="status">
        Loading replay: {status.loaded} of {status.total} entries
      </span>{' '}
      <button type="button" onClick={onCancel}>
        Cancel
      </button>
    </>
  );
}

type ReplayLoadingStatus = Extract<ReplayStatus, { phase: 'loading' }>;

function ReplayStart({ session }: { session: ReplaySession }) {
  const { status } = session;
  return (
    <div className="replay-start">
      {status.phase === 'loading' ? (
        <ReplayLoading status={status} onCancel={session.cancel} />
      ) : (
        <button type="button" onClick={session.start}>
          Replay
        </button>
      )}
      {status.phase === 'error' && (
        <p className="form-error" role="alert">
          Replay: {status.message}
        </p>
      )}
    </div>
  );
}

function ReplaySection({ replay }: { replay: AreaReplay }) {
  const { session, player, state } = replay;
  if (player === null) return <ReplayStart session={session} />;
  if (state === null) return null;
  return <ReplayControls player={player} state={state} onExit={session.exit} />;
}

interface AreaRoomProps {
  scene: ReturnType<typeof areaScene>;
  highlightId: string | null;
  replaying: boolean;
  loadError: string | null;
  selection: Selection;
  mapRef: RefObject<HTMLDivElement>;
}

/** The hovered entity's panel, from the truth (or the replay), next to its mark. */
function AreaHoverCard({ scene, selection }: Pick<AreaRoomProps, 'scene' | 'selection'>) {
  const { hover } = selection;
  const entity = hover ? scene.view.get(hover.id) : undefined;
  if (!hover || !entity) return null;
  return <HoverCard entity={entity} anchor={hover.anchor} />;
}

function AreaRoom(props: AreaRoomProps) {
  const { scene, highlightId, replaying, loadError, selection, mapRef } = props;
  const { width, height, entities, acting, actedUpon } = scene;
  return (
    <div className="area-room" data-replay={replaying ? 'true' : undefined} ref={mapRef}>
      {width !== null && height !== null ? (
        <RoomSvg
          width={width}
          height={height}
          entities={entities}
          highlightId={highlightId}
          selectedId={selection.selectedId}
          acting={acting}
          actedUpon={actedUpon}
          onSelect={selection.select}
          onHover={selection.onHover}
        />
      ) : (
        !loadError && <p>Loading area…</p>
      )}
      <AreaHoverCard scene={scene} selection={selection} />
    </div>
  );
}

interface LogSectionProps {
  logStore: LogStore;
  logEntries: readonly LogEntry[];
  replay: AreaReplay;
  view: ReadonlyMap<string, Resource>;
  onHighlight: (entityId: string | null) => void;
  onSelectEntity: (entityId: string) => void;
}

function LogSection(props: LogSectionProps) {
  const { logStore, logEntries, replay, view, onHighlight, onSelectEntity } = props;
  const logError = useLogError(logStore);
  const { player, state } = replay;
  return (
    <div className="area-log">
      <h2>Log</h2>
      {logError && <p className="form-error">Log: {logError}</p>}
      <LogPanel
        entries={player !== null ? player.replay.entries : logEntries}
        resources={view}
        onHighlight={onHighlight}
        currentSeq={state !== null && state.index >= 0 ? state.seq : null}
        onSelect={player !== null ? (seq) => player.seekSeq(seq) : undefined}
        onSelectEntity={onSelectEntity}
      />
    </div>
  );
}

interface AreaLayoutProps {
  gameId: string;
  scene: ReturnType<typeof areaScene>;
  connection: AreaConnection;
  replay: AreaReplay;
  store: ResourceStore;
  logStore: LogStore;
  logEntries: readonly LogEntry[];
}

function AreaInspector({
  scene,
  selection,
  isStale,
}: {
  scene: ReturnType<typeof areaScene>;
  selection: Selection;
  isStale: (id: string) => boolean;
}) {
  const { selectedId, select } = selection;
  return (
    <Inspector
      selected={selectedId === null ? undefined : scene.view.get(selectedId)}
      area={scene.area}
      resources={scene.view}
      onSelect={select}
      isStale={isStale}
    />
  );
}

/** The slim header: name, connection, game time and the replay controls. */
function AreaBar({
  gameId,
  scene,
  connection,
  replay,
}: Omit<AreaLayoutProps, 'store' | 'logStore' | 'logEntries'>) {
  return (
    <>
      <AreaHeader gameId={gameId} area={scene.area} connection={connection} />
      {replay.state === null && <GameTimeReadout time={scene.area?.time} />}
      {connection.loadError && <p className="form-error">{connection.loadError}</p>}
      <ReplaySection replay={replay} />
    </>
  );
}

function AreaLayout(props: AreaLayoutProps) {
  const { scene, connection, replay, store, logStore, logEntries } = props;
  const logProps = { logStore, logEntries, replay, view: scene.view };
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const mapRef = useRef<HTMLDivElement>(null);
  const selection = useSelection(() => mapRef.current);
  const replaying = replay.state !== null;
  const isStale = (id: string) => !replaying && store.isStale(id);
  return (
    <AreaScreen
      header={<AreaBar {...props} />}
      map={
        <AreaRoom
          scene={scene}
          highlightId={highlightId}
          replaying={replaying}
          loadError={connection.loadError}
          selection={selection}
          mapRef={mapRef}
        />
      }
      side={<AreaInspector scene={scene} selection={selection} isStale={isStale} />}
      log={
        <LogSection {...logProps} onHighlight={setHighlightId} onSelectEntity={selection.select} />
      }
    />
  );
}

/** The sprites' clock: running live, and in a replay while it plays, at the replay's speed. */
function useAreaSpriteClock(replayState: PlayerState | null) {
  return useOwnedSpriteClock({
    running: replayState === null || replayState.playing,
    rate: animationSpeed * (replayState?.speed ?? 1),
  });
}

function AreaLoaded({ gameId, areaId }: { gameId: string; areaId: string }) {
  const { authRequest } = useAuth();
  const [store] = useState(() => new ResourceStore());
  const resources = useResources(store);
  const logStore = useAreaLogStore(authRequest, areaId);
  const logEntries = useLogEntries(logStore);
  const animation = useAreaAnimation(areaId);
  const connection = useAreaConnection({ areaId, store, logStore, animation });
  const replay = useAreaReplay(authRequest, areaId);
  useFollowedNavigation(resources, areaId, gameId);
  useAnimateNewEntries(logEntries, store, animation);

  const scene = areaScene(areaId, resources, replay, animation);

  const clock = useAreaSpriteClock(replay.state);

  return (
    <SpriteClockContext.Provider value={clock}>
      <SpriteSheets />
      <AreaLayout
        key={areaId}
        gameId={gameId}
        scene={scene}
        connection={connection}
        replay={replay}
        store={store}
        logStore={logStore}
        logEntries={logEntries}
      />
    </SpriteClockContext.Provider>
  );
}
