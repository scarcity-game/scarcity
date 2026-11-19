import { useEffect, useState } from 'react';

/** Below this width the regions stack instead of sitting side by side. */
export const NARROW_QUERY = '(max-width: 720px)';

function matches(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(NARROW_QUERY).matches;
}

/** Whether the window is narrow; follows resizes. False where `matchMedia` is missing. */
export function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(matches);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(NARROW_QUERY);
    const onChange = () => setNarrow(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return narrow;
}
