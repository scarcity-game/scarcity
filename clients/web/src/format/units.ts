/**
 * The one place numbers are shaped for display. Every number the client shows
 * goes through here: at most 4 significant figures, no trailing zeros, and
 * physical quantities scaled to a unit that fits the amount. The exact value
 * is never lost: callers put `exactNumber` / `exactQuantity` / `exactJson` in
 * a `title` (game logs are information dense).
 */

/** Significant figures shown. */
export const SIG_FIGS = 4;

export type Quantity = 'mass' | 'length' | 'time' | 'speed';

interface Unit {
  symbol: string;
  /** How many base units one of these is. */
  factor: number;
}

interface Scale {
  /** The unit the server's numbers are in. */
  base: string;
  /** Ascending by factor. */
  units: readonly Unit[];
  /** Whether a rounded value carries a "~". */
  approximate: boolean;
}

const SCALES: Record<Quantity, Scale> = {
  // Stored in grams; Mg is the tonne.
  mass: {
    base: 'g',
    approximate: false,
    units: [
      { symbol: 'mg', factor: 1e-3 },
      { symbol: 'g', factor: 1 },
      { symbol: 'kg', factor: 1e3 },
      { symbol: 'Mg', factor: 1e6 },
      { symbol: 'Gg', factor: 1e9 },
    ],
  },
  length: {
    base: 'm',
    approximate: false,
    units: [
      { symbol: 'mm', factor: 1e-3 },
      { symbol: 'cm', factor: 1e-2 },
      { symbol: 'm', factor: 1 },
      { symbol: 'km', factor: 1e3 },
    ],
  },
  time: {
    base: 's',
    approximate: true,
    units: [
      { symbol: 'ms', factor: 1e-3 },
      { symbol: 's', factor: 1 },
      { symbol: 'min', factor: 60 },
      { symbol: 'h', factor: 3600 },
      { symbol: 'd', factor: 86400 },
    ],
  },
  speed: {
    base: 'm/s',
    approximate: false,
    units: [
      { symbol: 'mm/s', factor: 1e-3 },
      { symbol: 'm/s', factor: 1 },
      { symbol: 'km/s', factor: 1e3 },
    ],
  },
};

/** `value` to 4 significant figures, as a number (1234.567 is 1235). */
function roundSig(value: number): number {
  return Number(value.toPrecision(SIG_FIGS));
}

/** A number as shown: 4 significant figures, no trailing zeros (0.5, 1235, 0.0001235). */
export function formatNumber(value: number): string {
  return String(roundSig(value));
}

/** A number with every digit the server sent. */
export function exactNumber(value: number): string {
  return String(value);
}

/** Whether showing `value` drops digits (floating-point noise past 12 digits doesn't count). */
export function isRounded(value: number): boolean {
  return roundSig(value) !== Number(value.toPrecision(12));
}

function baseUnit(scale: Scale): Unit {
  return scale.units.find((unit) => unit.factor === 1)!;
}

/** The largest unit not bigger than the (rounded) amount; the smallest for tiny amounts, the base for 0. */
function unitFor(scale: Scale, value: number): Unit {
  if (value === 0) return baseUnit(scale);
  const size = Math.abs(roundSig(value));
  const fitting = scale.units.filter((unit) => unit.factor <= size);
  return fitting.length > 0 ? fitting[fitting.length - 1]! : scale.units[0]!;
}

/** `value`, in the unit of its quantity that fits it: 5000000 g is "5 Mg", 2149 s is "~35.82 min". */
export function formatQuantity(value: number, quantity: Quantity): string {
  const scale = SCALES[quantity];
  const unit = unitFor(scale, value);
  const scaled = value / unit.factor;
  const mark = scale.approximate && isRounded(scaled) ? '~' : '';
  return `${mark}${formatNumber(scaled)} ${unit.symbol}`;
}

/** The same value in the server's own unit, every digit: "2149 s". */
export function exactQuantity(value: number, quantity: Quantity): string {
  return `${exactNumber(value)} ${SCALES[quantity].base}`;
}

/** A point in metres, both numbers in the unit that fits the larger one: "(150, 20) cm". */
export function formatPoint(x: number, y: number): string {
  const unit = unitFor(SCALES.length, Math.max(Math.abs(x), Math.abs(y)));
  const part = (v: number) => formatNumber(v / unit.factor);
  return `(${part(x)}, ${part(y)}) ${unit.symbol}`;
}

/** A point in metres with every digit. */
export function exactPoint(x: number, y: number): string {
  return `(${exactNumber(x)}, ${exactNumber(y)}) m`;
}

function roundJsonValue(_key: string, value: unknown): unknown {
  return typeof value === 'number' && Number.isFinite(value) ? roundSig(value) : value;
}

/** JSON text with every number cut to 4 significant figures. */
export function compactJson(value: unknown): string {
  return JSON.stringify(value, roundJsonValue) ?? String(value);
}

/** JSON text with every digit. */
export function exactJson(value: unknown): string {
  return JSON.stringify(value) ?? String(value);
}
