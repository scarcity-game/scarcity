import { useId, type KeyboardEvent } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { PREFERENCE_SECTIONS, type PreferenceItem, type SwitchItem } from './catalogue.ts';
import { MessagesPreferences } from './MessagesPreferences.tsx';
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

function GeneralPreferences() {
  return (
    <>
      {PREFERENCE_SECTIONS.map((section) => (
        <fieldset key={section.title} className="pref-section">
          <legend>{section.title}</legend>
          {section.items.map((item) => (
            <PreferenceControl key={item.preference.storageKey} item={item} />
          ))}
        </fieldset>
      ))}
    </>
  );
}

const TABS = [
  { id: 'general', label: 'General' },
  { id: 'messages', label: 'Messages' },
] as const;
type TabId = (typeof TABS)[number]['id'];

function tabFrom(search: string | null): TabId {
  return TABS.find((t) => t.id === search)?.id ?? 'general';
}

function backTarget(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from;
  // Only a same-origin path: the state is set by our own link, but a history entry may be old.
  return typeof from === 'string' && /^\/(?!\/)/.test(from) ? from : '/';
}

/** The header's link to the page; it remembers where it was clicked so the page can go back. */
export function PreferencesLink() {
  const { pathname, search, state } = useLocation();
  const onPage = pathname === '/preferences';
  return (
    <Link to="/preferences" state={{ from: onPage ? backTarget(state) : pathname + search }}>
      Preferences
    </Link>
  );
}

function Tabs({ tab, select }: { tab: TabId; select: (id: TabId) => void }) {
  const onKeyDown = (e: KeyboardEvent) => {
    const step = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (step === 0) return;
    const index = (TABS.findIndex((t) => t.id === tab) + step + TABS.length) % TABS.length;
    const id = TABS[index]?.id ?? 'general';
    select(id);
    document.getElementById(`pref-tab-${id}`)?.focus();
  };
  return (
    <div role="tablist" aria-label="Preferences" className="pref-tabs" onKeyDown={onKeyDown}>
      {TABS.map((t) => (
        <button
          key={t.id}
          id={`pref-tab-${t.id}`}
          type="button"
          role="tab"
          aria-selected={tab === t.id}
          aria-controls="pref-panel"
          tabIndex={tab === t.id ? 0 : -1}
          onClick={() => select(t.id)}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

/**
 * The preferences page (`/preferences`): General and Messages tabs, the tab in `?tab=`. Every
 * choice takes effect at once and is remembered in this browser. "Back" returns to where the
 * link was clicked.
 */
export function PreferencesPage() {
  const { state } = useLocation();
  const [params, setParams] = useSearchParams();
  const tab = tabFrom(params.get('tab'));
  const select = (id: TabId) =>
    setParams(id === 'general' ? {} : { tab: id }, { replace: true, state });
  return (
    <section className="pref-page">
      <header className="pref-header">
        <h1>Preferences</h1>
        <Link to={backTarget(state)}>Back</Link>
      </header>
      <Tabs tab={tab} select={select} />
      <div id="pref-panel" role="tabpanel" aria-labelledby={`pref-tab-${tab}`}>
        {tab === 'general' ? <GeneralPreferences /> : <MessagesPreferences />}
      </div>
      <p className="pref-hint">Saved in this browser.</p>
    </section>
  );
}
