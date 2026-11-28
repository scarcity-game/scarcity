import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import {
  PREFERENCE_SECTIONS,
  type PreferenceItem,
  type PreferenceSection,
  type SwitchItem,
} from './catalogue.ts';
import { usePreference } from './store.ts';

function SwitchControl({ item }: { item: SwitchItem }) {
  const [on, set] = usePreference(item.preference);
  const hintId = useId();
  return (
    <div className="pref-item">
      <label>
        <input
          type="checkbox"
          checked={on}
          aria-describedby={hintId}
          onChange={(e) => set(e.target.checked)}
        />
        {item.label}
      </label>
      <p id={hintId} className="pref-hint">
        {item.hint}
      </p>
    </div>
  );
}

/** The control for one item, by its kind. */
function PreferenceControl({ item }: { item: PreferenceItem }) {
  switch (item.kind) {
    case 'switch':
      return <SwitchControl item={item} />;
  }
}

function Section({ section }: { section: PreferenceSection }) {
  return (
    <fieldset className="pref-section">
      <legend>{section.title}</legend>
      {section.items.map((item) => (
        <PreferenceControl key={item.preference.storageKey} item={item} />
      ))}
    </fieldset>
  );
}

/** The title and the Close button, which has the focus when the dialog opens. */
function DialogHeader({ titleId, onClose }: { titleId: string; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  useEffect(() => closeRef.current?.focus(), []);
  return (
    <header className="pref-header">
      <h2 id={titleId}>Preferences</h2>
      <button ref={closeRef} type="button" onClick={onClose}>
        Close
      </button>
    </header>
  );
}

/**
 * The preferences, as a modal over the page: every choice takes effect at once and is
 * remembered in this browser. Escape, the Close button or a click outside closes it.
 */
export function PreferencesDialog({ onClose }: { onClose: () => void }) {
  const titleId = useId();
  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key !== 'Escape') return;
    e.stopPropagation();
    onClose();
  };
  return (
    <div className="pref-backdrop" onClick={onClose}>
      <div
        className="pref-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <DialogHeader titleId={titleId} onClose={onClose} />
        {PREFERENCE_SECTIONS.map((section) => (
          <Section key={section.title} section={section} />
        ))}
        <p className="pref-hint">Saved in this browser.</p>
      </div>
    </div>
  );
}

/** The header's "Preferences" button; it opens the dialog and gets the focus back when it closes. */
export function PreferencesButton() {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const close = () => {
    setOpen(false);
    buttonRef.current?.focus();
  };
  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        Preferences
      </button>
      {open && <PreferencesDialog onClose={close} />}
    </>
  );
}
