import { useCallback, useEffect, useState } from 'react';
import type { Anchor } from './HoverCard.tsx';

/** The selected resource and the hovered mark: view state of one area page. */
export interface Selection {
  selectedId: string | null;
  select: (id: string | null) => void;
  hover: { id: string; anchor: Anchor } | null;
  onHover: (id: string | null, mark: Element | null) => void;
}

/** Where `mark` is inside the element that holds the map and its card. */
export function anchorOf(mark: Element, map: Element): Anchor {
  const m = mark.getBoundingClientRect();
  const r = map.getBoundingClientRect();
  return {
    left: m.left - r.left,
    top: m.top - r.top,
    width: m.width,
    height: m.height,
    mapWidth: r.width,
    mapHeight: r.height,
  };
}

/** Esc clears the selection. */
function useEscapeClears(select: (id: string | null) => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') select(null);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [select]);
}

/**
 * Selection (one at a time; Esc or the empty map clears it) and hover, for the
 * map and the panels. `map` returns the element the hover card is placed in.
 */
export function useSelection(map: () => Element | null): Selection {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [hover, setHover] = useState<Selection['hover']>(null);
  const select = useCallback((id: string | null) => setSelectedId(id), []);
  useEscapeClears(select);
  const onHover = (id: string | null, mark: Element | null) => {
    const container = map();
    setHover(id !== null && mark && container ? { id, anchor: anchorOf(mark, container) } : null);
  };
  return { selectedId, select, hover, onHover };
}
