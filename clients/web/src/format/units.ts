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
}

const SCALES: Record<Quantity, Scale> = {
  // Stored in grams; Mg is the tonne.
  mass: {
    base: 'g',
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
    units: [
      { symbol: 'mm', factor: 1e-3 },
      { symbol: 'cm', factor: 1e-2 },
      { symbol: 'm', factor: 1 },
      { symbol: 'km', factor: 1e3 },
    ],
  },
  time: {
    base: 's',
    units: [
      { symbol: 'ms', factor: 1e-3 },
      { symbol: 's', factor: 1 },
    ],
  },
  speed: {
    base: 'm/s',
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

const MINUTE = 60;
const HOUR = 3600;
const DAY = 86400;
const YEAR = 365 * DAY;

/** The clock part of a time: "m:ss" under an hour unless `withHours`, else "h:mm:ss". */
function clockOf(total: number, withHours: boolean): string {
  const h = Math.floor(total / HOUR) % 24;
  const m = Math.floor(total / MINUTE) % 60;
  const ss = String(total % 60).padStart(2, '0');
  if (total < HOUR && !withHours) return `${m}:${ss}`;
  return `${h}:${String(m).padStart(2, '0')}:${ss}`;
}

/** Whole seconds as "35:49", "15:07:12", "3d 15:07:12" or "2y 3d 15:07:12". */
function longTime(total: number): string {
  const years = Math.floor(total / YEAR);
  const days = Math.floor((total % YEAR) / DAY);
  const dated = total >= DAY;
  const clock = clockOf(total % YEAR, dated);
  const whole = dated ? `${days}d ${clock}` : clock;
  return years > 0 ? `${years}y ${whole}` : whole;
}

/**
 * A time in seconds: "250 ms" under a second, "12.35 s" under a minute, then a
 * clock ("35:49", "15:07:12"), with days ("3d 15:07:12") and years of 365 days
 * ("2y 3d 15:07:12"). The clock is whole seconds, so it never carries a "~".
 */
export function formatTime(seconds: number): string {
  const sign = seconds < 0 ? '-' : '';
  const size = Math.abs(seconds);
  if (size === 0) return '0 s';
  const shown = roundSig(size);
  if (shown < 1) return `${sign}${formatNumber(size * 1e3)} ms`;
  if (shown < MINUTE) return `${sign}${formatNumber(size)} s`;
  return sign + longTime(Math.round(size));
}

/** `value`, in the unit of its quantity that fits it: 5000000 g is "5 Mg", 2149 s is "35:49". */
export function formatQuantity(value: number, quantity: Quantity): string {
  if (quantity === 'time') return formatTime(value);
  const unit = unitFor(SCALES[quantity], value);
  return `${formatNumber(value / unit.factor)} ${unit.symbol}`;
}

/**
 * `formatQuantity` of the value cut to 2 significant figures ("2.9 m"): for a number that follows
 * the pointer (a move preview), where more digits only flicker. Never for a value the game holds.
 */
export function formatRoughQuantity(value: number, quantity: Quantity): string {
  return formatQuantity(Number(value.toPrecision(2)), quantity);
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
