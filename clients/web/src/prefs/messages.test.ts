import { beforeEach, describe, expect, it } from 'vitest';
import {
  CATEGORY_COLOURS,
  DEFAULT_MIN_LEVEL,
  MESSAGE_TYPES,
  colourPreference,
  messageColours,
  minLevelPreference,
  readMinLevel,
} from './messages.ts';
import { readPreference, writePreference } from './store.ts';

function luminance(hex: string): number {
  const channel = (i: number) => {
    const c = parseInt(hex.slice(1 + 2 * i, 3 + 2 * i), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2);
}
function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

describe('message catalogue', () => {
  it.each(MESSAGE_TYPES.map((t) => [`${t.category}/${t.id}`, t.defaults] as const))(
    'default colours of %s have a contrast of at least 4.5:1',
    (_name, { fg, bg }) => {
      expect(contrast(fg, bg)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it.each(Object.entries(CATEGORY_COLOURS))('category fallback %s reads well', (_c, { fg, bg }) => {
    expect(contrast(fg, bg)).toBeGreaterThanOrEqual(4.5);
  });

  it('has unique ids per category', () => {
    const keys = MESSAGE_TYPES.map((t) => `${t.category}/${t.id}`);
    expect(new Set(keys).size).toBe(keys.length);
  });
});

describe('message preferences', () => {
  beforeEach(() => window.localStorage.clear());

  it('minimum level defaults to 5 and stores only other values', () => {
    expect(readMinLevel('game')).toBe(DEFAULT_MIN_LEVEL);
    const pref = minLevelPreference('game');
    writePreference(pref, 2);
    expect(readMinLevel('game')).toBe(2);
    expect(window.localStorage.getItem('scarcity.minLevel.game.v1')).toBe('2');
    writePreference(pref, 5);
    expect(window.localStorage.getItem('scarcity.minLevel.game.v1')).toBeNull();
  });

  it('ignores a stored level outside 0 to 11', () => {
    window.localStorage.setItem('scarcity.minLevel.ooc.v1', '12');
    expect(readMinLevel('ooc')).toBe(DEFAULT_MIN_LEVEL);
    window.localStorage.setItem('scarcity.minLevel.ooc.v1', '"x"');
    expect(readMinLevel('ooc')).toBe(DEFAULT_MIN_LEVEL);
  });

  it('messageColours gives the default, the player choice, or the category fallback', () => {
    const death = MESSAGE_TYPES.find((t) => t.id === 'death')!;
    expect(messageColours('game', 'death')).toEqual(death.defaults);
    writePreference(colourPreference(death), { fg: '#000000', bg: '#ffffff' });
    expect(messageColours('game', 'death')).toEqual({ fg: '#000000', bg: '#ffffff' });
    expect(readPreference(colourPreference(death)).bg).toBe('#ffffff');
    expect(messageColours('game', 'no-such-type')).toEqual(CATEGORY_COLOURS.game);
    expect(messageColours('client', 'death')).toEqual(CATEGORY_COLOURS.client);
  });

  it('ignores stored colours that are not #rrggbb', () => {
    const chat = MESSAGE_TYPES.find((t) => t.id === 'chat')!;
    window.localStorage.setItem(
      'scarcity.messageColours.ooc.chat.v1',
      JSON.stringify({ fg: 'red', bg: '#ffffff' }),
    );
    expect(messageColours('ooc', 'chat')).toEqual(chat.defaults);
  });
});
