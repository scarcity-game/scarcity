import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import { RoomSvg } from '../components/RoomSvg.tsx';
import { ResourceStore, useResources, type Resource } from '../resources/cache.ts';
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
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  const [lastError, setLastError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const tokenRef = useRef(token);
  tokenRef.current = token;
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
          setStatus(s);
          setLastError(err);
        },
        onDigest: (area, versions) => {
          if (area === areaId) sync.handleDigest(versions);
        },
        onChanges: (area, changes) => {
          if (area === areaId) sync.handleChanges(changes);
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
  }, [authRequest, areaId, store]);

  const area = resources.get(areaId);
  const entities = (area?.children ?? [])
    .map((id) => resources.get(id))
    .filter((r): r is Resource => r !== undefined);
  const panel = area ? [area, ...entities] : entities;
  const width = typeof area?.state.width === 'number' ? area.state.width : null;
  const height = typeof area?.state.height === 'number' ? area.state.height : null;

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
      {loadError && <p className="form-error">{loadError}</p>}
      <div className="area-layout">
        <div className="area-room">
          {width !== null && height !== null ? (
            <RoomSvg width={width} height={height} entities={entities} />
          ) : (
            !loadError && <p>Loading area…</p>
          )}
        </div>
        <aside className="area-panel" aria-label="Resources">
          <ul className="resource-state-list">
            {panel.map((r) => (
              <li key={r.id} data-resource-id={r.id}>
                <strong>{r.type}</strong> <code>{r.id}</code>{' '}
                <span>
                  version <span className="resource-version">{r.version}</span>
                </span>
                {store.isStale(r.id) && <em> (stale)</em>}
                <pre>{JSON.stringify(r.state, null, 2)}</pre>
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </>
  );
}
