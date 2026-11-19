import type { CSSProperties, ReactNode } from 'react';
import { useLayoutSizes, type LayoutSizes } from './layoutStore.ts';
import { Splitter } from './Splitter.tsx';
import { useNarrow } from './useNarrow.ts';

export interface AreaScreenProps {
  /** The slim top bar: connection, game time, replay controls. */
  header: ReactNode;
  /** The centre; fills the space the others leave. */
  map: ReactNode;
  /** The inspector. */
  side: ReactNode;
  /** The log. */
  log: ReactNode;
}

interface SplittersProps {
  sizes: LayoutSizes;
  update: (patch: Partial<LayoutSizes>) => void;
  which: keyof LayoutSizes;
}

function Splitters({ sizes, update, which }: SplittersProps) {
  const vertical = which === 'side';
  return (
    <Splitter
      orientation={vertical ? 'vertical' : 'horizontal'}
      label={vertical ? 'Resize inspector' : 'Resize log'}
      value={sizes[which]}
      max={Math.round((vertical ? window.innerWidth : window.innerHeight) * (vertical ? 0.7 : 0.6))}
      onChange={(size) => update({ [which]: size })}
    />
  );
}

function sizeStyle(sizes: LayoutSizes): CSSProperties {
  return {
    '--side-size': `min(${sizes.side}px, 70%)`,
    '--log-size': `min(${sizes.log}px, 60%)`,
  } as CSSProperties;
}

/**
 * The area as an application screen: it fills its parent and never scrolls as a page; each region
 * scrolls inside itself. Wide: the map in the centre, the inspector on the side and the log below,
 * with splitters whose sizes are remembered per browser. Narrow: the regions stack, no splitters.
 */
export function AreaScreen({ header, map, side, log }: AreaScreenProps) {
  const narrow = useNarrow();
  const { sizes, update, reset } = useLayoutSizes();
  const splitter = (which: keyof LayoutSizes) =>
    narrow ? null : <Splitters sizes={sizes} update={update} which={which} />;
  return (
    <div
      className="area-screen"
      data-layout={narrow ? 'stacked' : 'split'}
      style={sizeStyle(sizes)}
    >
      <header className="area-bar">
        {header}
        {!narrow && (
          <button type="button" className="reset-layout" onClick={reset}>
            Reset layout
          </button>
        )}
      </header>
      <div className="area-region area-map">{map}</div>
      {splitter('side')}
      <div className="area-region area-side">{side}</div>
      {splitter('log')}
      <div className="area-region area-footer">{log}</div>
    </div>
  );
}
