import { booleanPreference, type Preference } from './store.ts';

/** Every preference the client has. Code reads one with `readPreference` or `usePreference`. */
export const PREFS = {
  /** With more than one advance waiting, the older ones snap to their end state (off: every action plays). */
  skipBacklog: booleanPreference('scarcity.skipBacklog.v1'),
  /** The map outlines every floor cell under the walls, and a click on one inspects it. */
  showFloorCells: booleanPreference('scarcity.showFloorCells.v1'),
};

/**
 * How the preferences dialog shows a preference. One kind per control; a new kind (a choice, a
 * slider) is a new member here and a new control in `PreferencesDialog.tsx`.
 */
export interface SwitchItem {
  kind: 'switch';
  preference: Preference<boolean>;
  label: string;
  hint: string;
}

export type PreferenceItem = SwitchItem;

export interface PreferenceSection {
  title: string;
  items: PreferenceItem[];
}

/** The preferences dialog, top to bottom. */
export const PREFERENCE_SECTIONS: PreferenceSection[] = [
  {
    title: 'Animation',
    items: [
      {
        kind: 'switch',
        preference: PREFS.skipBacklog,
        label: 'Skip animations when behind',
        hint: 'When actions arrive faster than they animate, jump the older ones to their end.',
      },
    ],
  },
  {
    title: 'Map',
    items: [
      {
        kind: 'switch',
        preference: PREFS.showFloorCells,
        label: 'Show floor cells',
        hint: 'Outline every floor cell under the walls; click one to inspect it.',
      },
    ],
  },
];
