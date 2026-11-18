import type { KeyboardEvent, MouseEvent } from 'react';
import { describe, drawText, entityOf, formatLogTime, reportedDraw } from './format.ts';
import type { LogNode } from './logTree.ts';
import type { LogEntry } from './types.ts';

/** What every entry of the log needs to render and react. */
export interface EntryView {
  /** The entry the replay is at; marked. */
  currentSeq: number | null;
  nameOf: (entityId: string) => string;
  /** Called with the entity an entry is about while it is hovered or focused, null otherwise. */
  onHighlight?: (entityId: string | null) => void;
  /** Called when an entry is clicked or activated with Enter or Space. */
  onSelect?: (seq: number) => void;
}

function isActivation(e: KeyboardEvent<HTMLElement>): boolean {
  return e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ');
}

function selectHandlers(seq: number, onSelect: EntryView['onSelect']) {
  if (!onSelect) return {};
  return {
    onClick: (e: MouseEvent<HTMLElement>) => {
      e.stopPropagation();
      onSelect(seq);
    },
    onKeyDown: (e: KeyboardEvent<HTMLElement>) => {
      if (!isActivation(e)) return;
      e.preventDefault();
      e.stopPropagation();
      onSelect(seq);
    },
  };
}

// Entries nest, and these events bubble: the innermost entry handles them.
function highlightHandlers(entityId: string | null, onHighlight: EntryView['onHighlight']) {
  const handler = (id: string | null) => (e: { stopPropagation(): void }) => {
    e.stopPropagation();
    onHighlight?.(id);
  };
  return {
    onMouseOver: handler(entityId),
    onMouseOut: handler(null),
    onFocus: handler(entityId),
    onBlur: handler(null),
  };
}

function LogDraws({ entry }: { entry: LogEntry }) {
  const reported = reportedDraw(entry);
  if (entry.draws.length === 0 && reported === null) return null;
  return (
    <ul className="log-draws">
      {reported !== null && (
        <li className="log-draw log-draw-reported">{drawText({ ...reported, for: 'reported' })}</li>
      )}
      {entry.draws.map((draw, i) => (
        <li key={i} className="log-draw">
          {drawText(draw)}
        </li>
      ))}
    </ul>
  );
}

function LogEntryHead({ entry, nameOf }: { entry: LogEntry; nameOf: EntryView['nameOf'] }) {
  const entityId = entityOf(entry);
  return (
    <>
      <span className="log-time">{formatLogTime(entry.time)}</span>{' '}
      <span className="log-kind">{entry.kind}</span>{' '}
      {entityId !== null && (
        <>
          <span className="log-name">{nameOf(entityId)}</span>{' '}
        </>
      )}
      <span className="log-what">{describe(entry)}</span>
    </>
  );
}

export function LogEntryItem({ node, view }: { node: LogNode; view: EntryView }) {
  const { entry } = node;
  const { currentSeq, nameOf, onSelect, onHighlight } = view;
  return (
    <li
      className={entry.seq === currentSeq ? 'log-entry log-current' : 'log-entry'}
      data-seq={entry.seq}
      data-kind={entry.kind}
      tabIndex={0}
      aria-current={entry.seq === currentSeq ? 'true' : undefined}
      {...selectHandlers(entry.seq, onSelect)}
      {...highlightHandlers(entityOf(entry), onHighlight)}
    >
      <LogEntryHead entry={entry} nameOf={nameOf} />
      <LogDraws entry={entry} />
      {node.children.length > 0 && (
        <ul className="log-children">
          {node.children.map((child) => (
            <LogEntryItem key={child.entry.seq} node={child} view={view} />
          ))}
        </ul>
      )}
    </li>
  );
}
