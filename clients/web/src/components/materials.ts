/** Flat fill per `material` until patterns come from `content/sprites`. */
const FILLS: Record<string, string> = {
  stone: '#7b7f87',
  wood: '#8b6b45',
  metal: '#9aa5b1',
};

export const NEUTRAL_FILL = '#8a8a8a';

export function fillOf(material: unknown): string {
  return typeof material === 'string' ? (FILLS[material] ?? NEUTRAL_FILL) : NEUTRAL_FILL;
}
