import { type Preference, readPreference, usePreference } from './store.ts';

/** A message's category: one tab of the Messages panel (epic #261). */
export type MessageCategory = 'game' | 'client' | 'server' | 'ooc';

export const MESSAGE_CATEGORIES: { id: MessageCategory; label: string }[] = [
  { id: 'game', label: 'Game' },
  { id: 'client', label: 'Client' },
  { id: 'server', label: 'Server' },
  { id: 'ooc', label: 'OOC' },
];

export const MIN_LEVEL = 0;
export const MAX_LEVEL = 11;
/** Messages under this level are hidden unless the player chooses another minimum. */
export const DEFAULT_MIN_LEVEL = 5;

export interface MessageColours {
  /** `#rrggbb` */
  fg: string;
  bg: string;
}

export interface MessageType {
  id: string;
  label: string;
  category: MessageCategory;
  defaults: MessageColours;
}

const pair = (fg: string, bg: string): MessageColours => ({ fg, bg });

/** What a type the catalogue doesn't know looks like, by category. */
export const CATEGORY_COLOURS: Record<MessageCategory, MessageColours> = {
  game: pair('#374151', '#f3f4f6'),
  client: pair('#7f1d1d', '#fee2e2'),
  server: pair('#713f12', '#fef9c3'),
  ooc: pair('#1f2937', '#e0f2fe'),
};

/**
 * Every message type the client knows. A new type is one entry; the page lists it under its
 * category. Routine types are muted, errors and deaths stand out; every default has a contrast
 * of at least 4.5:1 (`messages.test.ts`).
 */
export const MESSAGE_TYPES: MessageType[] = [
  { id: 'movement', label: 'Movement', category: 'game', defaults: pair('#4b5563', '#f3f4f6') },
  { id: 'intent', label: 'Intent', category: 'game', defaults: pair('#1e3a8a', '#dbeafe') },
  { id: 'attack', label: 'Attack', category: 'game', defaults: pair('#7c2d12', '#ffedd5') },
  { id: 'damage', label: 'Damage', category: 'game', defaults: pair('#7c2d12', '#fdba74') },
  { id: 'death', label: 'Death', category: 'game', defaults: pair('#ffffff', '#991b1b') },
  { id: 'wait', label: 'Wait', category: 'game', defaults: pair('#4b5563', '#f9fafb') },
  { id: 'event', label: 'Event', category: 'game', defaults: pair('#374151', '#f3f4f6') },
  { id: 'turn', label: 'Turn', category: 'game', defaults: pair('#4b5563', '#e5e7eb') },
  { id: 'snapshot', label: 'Snapshot', category: 'game', defaults: pair('#4b5563', '#e5e7eb') },
  {
    id: 'rest-error',
    label: 'Request failed',
    category: 'client',
    defaults: pair('#ffffff', '#b91c1c'),
  },
  {
    id: 'ws-error',
    label: 'Connection lost',
    category: 'client',
    defaults: pair('#ffffff', '#7f1d1d'),
  },
  {
    id: 'connection',
    label: 'Connection',
    category: 'client',
    defaults: pair('#14532d', '#dcfce7'),
  },
  { id: 'system', label: 'System', category: 'server', defaults: pair('#713f12', '#fef08a') },
  { id: 'chat', label: 'Chat', category: 'ooc', defaults: pair('#1f2937', '#e0f2fe') },
  {
    id: 'direct-message',
    label: 'Direct message',
    category: 'ooc',
    defaults: pair('#4c1d95', '#ede9fe'),
  },
];

const colourPreferences = new Map<string, Preference<MessageColours>>();

function isColour(value: unknown): value is string {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value);
}

/** The stored colours of one catalogue type (`scarcity.messageColours.<category>.<type>.v1`). */
export function colourPreference(type: MessageType): Preference<MessageColours> {
  const key = `${type.category}.${type.id}`;
  let preference = colourPreferences.get(key);
  if (!preference) {
    preference = {
      storageKey: `scarcity.messageColours.${key}.v1`,
      fallback: type.defaults,
      parse: (stored) => {
        const s = stored as Partial<MessageColours> | null;
        return s && isColour(s.fg) && isColour(s.bg) ? { fg: s.fg, bg: s.bg } : undefined;
      },
    };
    colourPreferences.set(key, preference);
  }
  return preference;
}

/** The colours for a message of this category and type: the player's, else the default, else the category's. */
export function messageColours(category: MessageCategory, type: string): MessageColours {
  const known = MESSAGE_TYPES.find((t) => t.category === category && t.id === type);
  return known ? readPreference(colourPreference(known)) : CATEGORY_COLOURS[category];
}

const levelPreferences = new Map<MessageCategory, Preference<number>>();

/** The minimum level shown in a category (`scarcity.minLevel.<category>.v1`, 0 to 11, default 5). */
export function minLevelPreference(category: MessageCategory): Preference<number> {
  let preference = levelPreferences.get(category);
  if (!preference) {
    preference = {
      storageKey: `scarcity.minLevel.${category}.v1`,
      fallback: DEFAULT_MIN_LEVEL,
      parse: (stored) =>
        typeof stored === 'number' &&
        Number.isInteger(stored) &&
        stored >= MIN_LEVEL &&
        stored <= MAX_LEVEL
          ? stored
          : undefined,
    };
    levelPreferences.set(category, preference);
  }
  return preference;
}

/** The category's minimum level outside React; a message is shown when its level is at least this. */
export function readMinLevel(category: MessageCategory): number {
  return readPreference(minLevelPreference(category));
}

/** The category's minimum level and its setter, for the Messages panel (#263) and the page. */
export function useMinLevel(category: MessageCategory): [number, (level: number) => void] {
  return usePreference(minLevelPreference(category));
}
