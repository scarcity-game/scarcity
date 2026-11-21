import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { errorMessage, isAbortError } from '../api/client.ts';
import { useAuth } from '../auth/AuthProvider.tsx';
import type { Resource } from '../resources/cache.ts';
import { areaPath } from '../resources/routeIds.ts';
import { listGameAreas } from '../resources/worldApi.ts';
import { resourceName } from './GamesView.tsx';

type Filter = 'active' | 'all';

interface AreaPages {
  areas: Resource[];
  total: number;
  loading: boolean;
  error: string | null;
  loadMore: () => void;
}

interface Loaded {
  areas: Resource[];
  total: number;
  loading: boolean;
  error: string | null;
}

/** Fetches page `wanted` (1-based) of the filter; page 1 replaces the rows, later pages add to them. */
function useFetchedPages(gameId: string, filter: Filter, wanted: number) {
  const { authRequest } = useAuth();
  const [loaded, setLoaded] = useState<Loaded>({ areas: [], total: 0, loading: true, error: null });
  useEffect(() => {
    const controller = new AbortController();
    const activeOnly = filter === 'active';
    listGameAreas(authRequest, gameId, { activeOnly, page: wanted - 1, signal: controller.signal })
      .then((page) => {
        setLoaded((b) => ({
          areas: wanted === 1 ? page.items : [...b.areas, ...page.items],
          total: page.total,
          loading: false,
          error: null,
        }));
      })
      .catch((err: unknown) => {
        if (!isAbortError(err))
          setLoaded((b) => ({ ...b, loading: false, error: errorMessage(err) }));
      });
    return () => controller.abort();
  }, [authRequest, gameId, filter, wanted]);
  return [loaded, setLoaded] as const;
}

/** The areas of the filter, a page at a time; a new filter or game starts again from page 0. */
function useAreaPages(gameId: string, filter: Filter): AreaPages {
  const [wanted, setWanted] = useState(1);
  const [key, setKey] = useState(`${gameId} ${filter}`);
  const [loaded, setLoaded] = useFetchedPages(gameId, filter, wanted);
  if (key !== `${gameId} ${filter}`) {
    setKey(`${gameId} ${filter}`);
    setWanted(1);
    setLoaded({ areas: [], total: 0, loading: true, error: null });
  }
  const loadMore = useCallback(() => {
    setLoaded((b) => ({ ...b, loading: true }));
    setWanted((n) => n + 1);
  }, [setLoaded]);
  return { ...loaded, loadMore };
}

function activeText(area: Resource): string | null {
  const active = area.activeEntity as { entity?: unknown; controller?: Record<string, unknown> };
  if (!active || typeof active !== 'object') return null;
  const c = active.controller;
  const who = c
    ? `${String(c.kind)} ${String(c.principal ?? c.sub ?? '')}`.trim()
    : 'no controller';
  return `${String(active.entity)} (${who})`;
}

function AreaFacts({ area }: { area: Resource }) {
  const active = activeText(area);
  const facts = [
    typeof area.depth === 'number' ? `depth ${area.depth}` : null,
    typeof area.time === 'number' ? `time ${area.time}` : null,
    `${area.children.length} ${area.children.length === 1 ? 'entity' : 'entities'}`,
  ].filter((f) => f !== null);
  return (
    <>
      <span className="area-facts">{facts.join(' · ')}</span>
      {active && <span className="area-active"> Active: {active}</span>}
    </>
  );
}

function AreaRows({ gameId, areas }: { gameId: string; areas: Resource[] }) {
  return (
    <ul className="resource-list area-rows">
      {areas.map((area) => (
        <li key={area.id}>
          <Link to={areaPath(gameId, area.id)}>{resourceName(area)}</Link> <AreaFacts area={area} />
        </li>
      ))}
    </ul>
  );
}

function FilterChoice({ filter, onChange }: { filter: Filter; onChange: (f: Filter) => void }) {
  return (
    <fieldset className="area-filter">
      <legend>Show</legend>
      {(['active', 'all'] as const).map((choice) => (
        <label key={choice}>
          <input
            type="radio"
            name="area-filter"
            checked={filter === choice}
            onChange={() => onChange(choice)}
          />{' '}
          {choice === 'active' ? 'Active' : 'All'}
        </label>
      ))}
    </fieldset>
  );
}

function EmptyState({ filter, onAll }: { filter: Filter; onAll: () => void }) {
  if (filter === 'all') return <p>This game has no areas.</p>;
  return (
    <p>
      No active areas.{' '}
      <button type="button" className="link-button" onClick={onAll}>
        Show all areas
      </button>
    </p>
  );
}

function Paging({ pages }: { pages: AreaPages }) {
  const { areas, total, loading, loadMore } = pages;
  return (
    <p>
      Showing {areas.length} of {total}.{' '}
      {areas.length < total && (
        <button type="button" disabled={loading} onClick={loadMore}>
          Load more
        </button>
      )}
    </p>
  );
}

function Status({ filter, pages, onAll }: { filter: Filter; pages: AreaPages; onAll: () => void }) {
  const { areas, loading, error } = pages;
  if (error) return <p className="form-error">{error}</p>;
  if (areas.length > 0) return null;
  return loading ? <p>Loading areas…</p> : <EmptyState filter={filter} onAll={onAll} />;
}

function AreaBody({
  gameId,
  filter,
  pages,
  onAll,
}: {
  gameId: string;
  filter: Filter;
  pages: AreaPages;
  onAll: () => void;
}) {
  return (
    <>
      <Status filter={filter} pages={pages} onAll={onAll} />
      <AreaRows gameId={gameId} areas={pages.areas} />
      {pages.areas.length > 0 && <Paging pages={pages} />}
    </>
  );
}

/**
 * The Active/All filter (`?show=all`) and the paged rows of a game's areas. `defaultFilter` is
 * the filter until the URL says otherwise (`?show=active` or `?show=all`).
 */
export function AreaListPanel({
  gameId,
  defaultFilter = 'active',
}: {
  gameId: string;
  defaultFilter?: Filter;
}) {
  const [search, setSearch] = useSearchParams();
  const shown = search.get('show');
  const filter: Filter = shown === 'all' || shown === 'active' ? shown : defaultFilter;
  const pages = useAreaPages(gameId, filter);
  const choose = (f: Filter) => setSearch({ show: f }, { replace: true });
  return (
    <>
      <FilterChoice filter={filter} onChange={choose} />
      <AreaBody gameId={gameId} filter={filter} pages={pages} onAll={() => choose('all')} />
    </>
  );
}
