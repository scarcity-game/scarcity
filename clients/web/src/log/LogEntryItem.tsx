import type { KeyboardEvent, MouseEvent } from 'react';
import {
  contactPartner,
  describe,
  drawText,
  exactDetail,
  exactDraw,
  exactLogTime,
  entityOf,
  formatLogTime,
  reportedDraw,
} from './format.ts';
import type { LogEntry } from './types.ts';

/** What every entry of the log needs to render and react. */
export interface EntryView {
  /** The entry the replay is at; marked. */
  currentSeq: number | null;
  nameOf: (entityId: string) => string;
  /** The loaded entry with this seq, if any: an entry's cause, shown as a link. */
  entryOf?: (seq: number) => LogEntry | undefined;
  /** Called with the entity an entry is about while it is hovered or focused, null otherwise. */
  onHighlight?: (entityId: string | null) => void;
  /** Called when an entry is clicked or activated with Enter or Space. */
  onSelect?: (seq: number) => void;
  /** Called when the entity's name in an entry is clicked: it selects the entity (#79). */
  onSelectEntity?: (entityId: string) => void;
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

// These events bubble from the row's buttons; the row handles them once.
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
        <li className="log-draw log-draw-reported" title={exactDraw(reported)}>{drawText({ ...reported, for: 'reported' })}</li>
      )}
      {entry.draws.map((draw, i) => (
        <li key={i} className="log-draw" title={exactDraw(draw)}>
          {drawText(draw)}
        </li>
      ))}
    </ul>
  );
}

function LogName({ entityId, view }: { entityId: string; view: EntryView }) {
  const { nameOf, onSelectEntity } = view;
  if (!onSelectEntity) return <span className="log-name">{nameOf(entityId)}</span>;
  return (
    <button
      type="button"
      className="log-name"
      onClick={(e) => {
        e.stopPropagation();
        onSelectEntity(entityId);
      }}
      onKeyDown={(e) => e.stopPropagation()}
    >
      {nameOf(entityId)}
    </button>
  );
}

function LogEntryHead({ entry, view }: { entry: LogEntry; view: EntryView }) {
  const entityId = entityOf(entry);
  return (
    <>
      <span className="log-time" title={exactLogTime(entry.time)}>{formatLogTime(entry.time)}</span>{' '}
      <span className="log-kind">{entry.kind}</span>{' '}
      {entityId !== null && (
        <>
          <LogName entityId={entityId} view={view} />{' '}
        </>
      )}
      <span className="log-what" title={exactDetail(entry)}>{describe(entry)}</span>
    </>
  );
}

/**
 * What set this entry off, when that happened at an earlier game time (a
 * rubble's decay, set off by the throw twenty seconds before): a link to it.
 * A cause at the same time is the entry just above, so it gets none.
 */
function LogCause({ entry, view }: { entry: LogEntry; view: EntryView }) {
  const cause = entry.cause === null ? undefined : view.entryOf?.(entry.cause);
  if (cause === undefined || cause.time === entry.time) return null;
  const label = `from ${formatLogTime(cause.time)} ${describe(cause)}`;
  const { onSelect } = view;
  if (!onSelect) return <span className="log-cause"> ({label})</span>;
  return (
    <>
      {' '}
      <button
        type="button"
        className="log-cause"
        aria-label={`Go to its cause: ${label}`}
        onClick={(e) => {
          e.stopPropagation();
          onSelect(cause.seq);
        }}
        onKeyDown={(e) => e.stopPropagation()}
      >
        ← {label}
      </button>
    </>
  );
}

/** What interrupted the action of an `action-interrupted` entry: an effect, or a contact with an entity or the boundary. */
function interruptedBy(entry: LogEntry, view: EntryView): string | null {
  const cause = entry.cause === null ? undefined : view.entryOf?.(entry.cause);
  if (cause === undefined) return null;
  if (cause.kind === 'effect') return `by the ${effectType(cause)} effect`;
  return cause.kind === 'contact' ? contactText(cause, entry.resource, view) : null;
}

const effectType = (effect: LogEntry): string => effect.loggedEffect?.type ?? '';

function contactText(contact: LogEntry, mover: string | null, view: EntryView): string {
  const partner = contactPartner(contact, mover);
  return partner === null
    ? "by contact with the area's boundary"
    : `by contact with ${view.nameOf(partner)}`;
}

function LogInterruption({ entry, view }: { entry: LogEntry; view: EntryView }) {
  if (entry.kind !== 'action-interrupted') return null;
  const by = interruptedBy(entry, view);
  return by === null ? null : <span className="log-interrupted-by"> {by}</span>;
}

/** The resources an effect acted upon that had an action in progress, and whether it was interrupted. */
function LogActedUpon({ entry, view }: { entry: LogEntry; view: EntryView }) {
  const acted = entry.actedUpon ?? [];
  if (acted.length === 0) return null;
  return (
    <ul className="log-acted-upon">
      {acted.map(({ resource, interrupted }) => (
        <li key={resource}>
          acted upon: {view.nameOf(resource)},{' '}
          {interrupted ? 'action interrupted' : 'in recovery, not interrupted'}
        </li>
      ))}
    </ul>
  );
}

export function LogEntryItem({ entry, view }: { entry: LogEntry; view: EntryView }) {
  const { currentSeq, onSelect, onHighlight } = view;
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
      <LogEntryHead entry={entry} view={view} />
      <LogInterruption entry={entry} view={view} />
      <LogCause entry={entry} view={view} />
      <LogActedUpon entry={entry} view={view} />
      <LogDraws entry={entry} />
    </li>
  );
}
