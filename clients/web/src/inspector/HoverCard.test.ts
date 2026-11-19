import { describe, expect, it } from 'vitest';
import { cardPosition } from './HoverCard.tsx';

const map = { mapWidth: 400, mapHeight: 300, width: 20, height: 20 };

describe('cardPosition', () => {
  it('puts the card right of and level with a mark in the top-left quarter', () => {
    expect(cardPosition({ ...map, left: 50, top: 40 })).toEqual({ left: 78, top: 40 });
  });

  it('puts the card left of and above-aligned for a mark in the bottom-right quarter', () => {
    expect(cardPosition({ ...map, left: 350, top: 250 })).toEqual({ right: 58, bottom: 30 });
  });
});
