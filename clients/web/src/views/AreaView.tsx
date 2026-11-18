import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import { GameTimeReadout } from '../components/GameTimeReadout.tsx';
import { useAreaAnimation } from '../game/useAreaAnimation.ts';
import { RoomSvg } from '../components/RoomSvg.tsx';
import { LogPanel } from '../log/LogPanel.tsx';
import { fetchLogPage } from '../log/logApi.ts';
import { LogStore, useLogEntries, useLogError } from '../log/logStore.ts';
import { ResourceStore, useResources, type Resource } from '../resources/cache.ts';
import { followedEntity } from '../resources/followedArea.ts';
import { gamePath, segmentToId } from '../resources/routeIds.ts';
import { AreaSync } from '../resources/sync.ts';
import { getResource, listAllChildren } from '../resources/worldApi.ts';
import {
  SubscriptionsClient,
  defaultSocketUrl,
  type ConnectionStatus,
} from '../ws/subscriptionsClient.ts';
import { resourceName } from './GamesView.tsx';
import { NotFoundView } from './NotFoundView.tsx';
import { ReplayControls } from '../replay/ReplayControls.tsx';
import { useOptionalReplayState } from '../replay/player.ts';
import { useReplaySession } from '../replay/useReplaySession.ts';

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

function AreaLoaded({ gameId, areaId }: { gameId: string; areaId: string }) {
  const { authRequest, token, rejectSession } = useAuth();
  const [store] = useState(() => new ResourceStore());
  const resources = useResources(store);
  const [logStore] = useState(
    () =>
      new LogStore(
        (after, limit, signal) =>
          fetchLogPage(authRequestRef.current, areaIdRef.current, after, limit, signal),
        { pollMs: 5000 },
      ),
  );
  const logEntries = useLogEntries(logStore);
  const animation = useAreaAnimation(areaId);
  const logError = useLogError(logStore);
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  const [lastError, setLastError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const session = useReplaySession((after, limit, kind, signal) => fetchLogPage(authRequestRef.current, areaIdRef.current, after, limit, signal, kind));
  const replayPlayer = session.status.phase === 'active' ? session.status.player : null;
  const replayState = useOptionalReplayState(replayPlayer);

  const authRequestRef = useRef(authRequest);
  authRequestRef.current = authRequest;
  const areaIdRef = useRef(areaId);
  areaIdRef.current = areaId;
  const tokenRef = useRef(token);
  tokenRef.current = token;
  const animationRef = useRef(animation);
  animationRef.current = animation;
  const rejectRef = useRef(rejectSession);
  rejectRef.current = rejectSession;

  useEffect(() => {
    store.clear();
    setLoadError(null);
    const controller = new AbortController();
    const sync = new AreaSync(
      areaId,
      store,
      (id, cachedVersion) =>
        getResource(authRequest, id, {
          cachedVersion,
          signal: controller.signal,
        }),
      (message) => setLoadError(message),
    );
    const client = new SubscriptionsClient({
      url: defaultSocketUrl(),
      getToken: () => tokenRef.current,
      handlers: {
        onStatus: (s, err) => {
          logStore.setLive(s === 'live');
          setStatus(s);
          setLastError(err);
        },
        onDigest: (area, versions, logSeq) => {
          if (area === areaId) {
            sync.handleDigest(versions);
            if (logSeq !== undefined) logStore.notifyLogSeq(logSeq);
          }
        },
        onChanges: (area, changes, logSeq) => {
          if (area === areaId) {
            // Keep the state before this advance up until its log entries arrive (they are animated).
            if (logSeq !== undefined) animationRef.current.hold(store.getSnapshot());
            sync.handleChanges(changes);
            if (logSeq !== undefined) logStore.notifyLogSeq(logSeq);
          }
        },
        onError: (message) => setLastError(message),
        onUnauthorized: () => rejectRef.current('unauthorized'),
      },
    });

    // Initial load: the area, then all pages of its children.
    void (async () => {
      try {
        const area = await getResource(authRequest, areaId, {
          signal: controller.signal,
        });
        if (area) store.put(area);
        const children = await listAllChildren(authRequest, areaId, controller.signal);
        for (const child of children) store.put(child);
      } catch (err) {
        if (!isAbortError(err)) setLoadError(errorMessage(err));
      }
    })();

    client.subscribe(areaId);
    client.start();
    return () => {
      controller.abort();
      sync.close();
      client.unsubscribe(areaId);
      client.stop();
    };
  }, [authRequest, areaId, store, logStore]);

  // A spectator follows the game's player (or npc): when it leaves this area (stairs), the
  // game page finds the area it is in now.
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

  // The log is read only while this view is open; it follows logSeq while the socket is live and polls otherwise.
  useEffect(() => {
    logStore.clear();
    logStore.start();
    return () => logStore.stop();
  }, [logStore, areaId]);

  // The log entries that arrived are animated on top of the state before them.
  useEffect(() => {
    animation.follow(logEntries, store.getSnapshot());
  }, [logEntries]);

  // `view` is the truth (or the replay): the panels and the readout show it. The room shows `shown`.
  const view: ReadonlyMap<string, Resource> = replayState ? replayState.resources : resources;
  const area = view.get(areaId);
  const frame = replayState ? { resources: view, acting: undefined } : animation.frame(resources);
  const shown = frame.resources;
  const entities = (shown.get(areaId)?.children ?? area?.children ?? [])
    .map((id) => shown.get(id))
    .filter((r): r is Resource => r !== undefined);
  const truthEntities = (area?.children ?? [])
    .map((id) => view.get(id))
    .filter((r): r is Resource => r !== undefined);
  const panel = area ? [area, ...truthEntities] : truthEntities;
  const width = typeof area?.width === 'number' ? area.width : null;
  const height = typeof area?.height === 'number' ? area.height : null;

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
      {replayState === null && <GameTimeReadout time={area?.time} />}
      {loadError && <p className="form-error">{loadError}</p>}
      {replayPlayer === null && (
        <div className="replay-start">
          {session.status.phase === 'loading' ? (
            <>
              <progress aria-label="Loading replay" max={Math.max(session.status.total, 1)} value={session.status.loaded} />{' '}
              <span role="status">Loading replay: {session.status.loaded} of {session.status.total} entries</span>{' '}
              <button type="button" onClick={session.cancel}>Cancel</button>
            </>
          ) : (
            <button type="button" onClick={session.start}>Replay</button>
          )}
          {session.status.phase === 'error' && <p className="form-error" role="alert">Replay: {session.status.message}</p>}
        </div>
      )}
      {replayPlayer !== null && replayState !== null && (
        <ReplayControls player={replayPlayer} state={replayState} onExit={session.exit} />
      )}
      <div className="area-layout">
        <div className="area-room" data-replay={replayState !== null ? 'true' : undefined}>
          {width !== null && height !== null ? (
            <RoomSvg
              width={width}
              height={height}
              entities={entities}
              highlightId={highlightId}
              acting={frame.acting}
            />
          ) : (
            !loadError && <p>Loading area…</p>
          )}
        </div>
        <aside className="area-panel" aria-label="Resources">
          <ul className="resource-state-list">
            {panel.map((r) => (
              <li key={r.id} data-resource-id={r.id}>
                <strong>{r.resourceKind}</strong> <code>{r.id}</code>{' '}
                <span>
                  version <span className="resource-version">{r.version}</span>
                </span>
                {replayState === null && store.isStale(r.id) && <em> (stale)</em>}
                <pre>{JSON.stringify(r, null, 2)}</pre>
              </li>
            ))}
          </ul>
        </aside>
        <div className="area-log">
          <h2>Log</h2>
          {logError && <p className="form-error">Log: {logError}</p>}
          <LogPanel
            entries={replayPlayer !== null ? replayPlayer.replay.entries : logEntries}
            resources={view}
            onHighlight={setHighlightId}
            currentSeq={replayState !== null && replayState.index >= 0 ? replayState.seq : null}
            onSelect={replayPlayer !== null ? (seq) => replayPlayer.seekSeq(seq) : undefined}
          />
        </div>
      </div>
    </>
  );
}
