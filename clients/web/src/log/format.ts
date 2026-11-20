import type { LogBy, LogDraw, LogEntry } from './types.ts'

export function fmtNumber(n: number): string {
  return Number.isInteger(n) ? n.toFixed(1) : String(n)
}

function isRange(v: unknown): v is { min: number; max: number } {
  return typeof v === 'object' && v !== null && 'min' in v && 'max' in v
}

function isLiteral(v: unknown): boolean {
  return typeof v === 'boolean' || v === null || v === undefined
}

export function fmtValue(v: unknown): string {
  if (typeof v === 'number') return fmtNumber(v)
  if (isLiteral(v)) return String(v)
  if (isRange(v)) return `[${fmtNumber(v.min)}, ${fmtNumber(v.max)})`
  if (Array.isArray(v)) return `[${v.map(fmtValue).join(', ')}]`
  return JSON.stringify(v)
}

export function drawText(d: LogDraw): string {
  const parts = [(d.for ?? 'draw') + ': ' + d.dist]
  for (const [key, value] of Object.entries(d)) {
    if (key === 'for' || key === 'dist' || key === 'value') continue
    parts.push(`${key}=${fmtValue(value)}`)
  }
  parts.push(`-> value=${d.dist === 'categorical' ? String(d.value) : fmtValue(d.value)}`)
  return parts.join(' ')
}

export function shortId(id: string): string {
  const [prefix, ...rest] = id.split(':')
  const suffix = rest.join(':')
  return `${prefix}:…${suffix.slice(-4)}`
}

function effectParam(e: LogEntry, key: 'target' | 'source'): string | null {
  const value = e.loggedEffect?.params?.[key]
  return value && typeof value === 'string' ? value : null
}

export function entityOf(e: LogEntry): string|null {
  if (e.resource) return e.resource
  const target = effectParam(e, 'target')
  if (target !== null) return target
  const firstEntityChange = (e.changes ?? []).find(c => c.type === 'entity')
  if (firstEntityChange) return firstEntityChange.id
  return effectParam(e, 'source')
}

/** The controller that sent an intent: a service's principal, or a user's short id. */
export function controllerName(by: LogBy | undefined): string {
  if (by === undefined) return 'controller'
  if (by.kind === 'service' && by.principal) return by.principal
  if (by.kind === 'user' && by.sub) return 'user …' + by.sub.slice(-4)
  return by.kind
}

/** The draw a controller reported with its intent (`reported.draw`), as the log carries it, or null. */
export function reportedDraw(e: LogEntry): LogDraw | null {
  const reported = e.reported
  if (typeof reported !== 'object' || reported === null) return null
  const draw = (reported as { draw?: unknown }).draw
  if (typeof draw !== 'object' || draw === null || Array.isArray(draw)) return null
  if (typeof (draw as { dist?: unknown }).dist !== 'string') return null
  return draw as LogDraw
}

/** The probability of a reported categorical draw's value (weights[value] / sum), or null. */
function reportedP(draw: LogDraw): number | null {
  const weights = draw.weights
  if (draw.dist !== 'categorical' || !Array.isArray(weights) || typeof draw.value !== 'number') return null
  const w = weights[draw.value]
  const sum = weights.reduce((a: number, b: unknown) => a + (typeof b === 'number' ? b : 0), 0)
  return typeof w === 'number' && sum > 0 ? w / sum : null
}

function describeIntent(e: LogEntry): string {
  const text = controllerName(e.controller) + ' chose ' + (e.actionSpec?.type ?? 'an action')
  const draw = reportedDraw(e)
  if (draw === null) return text
  const parts: string[] = []
  const p = reportedP(draw)
  if (p !== null) parts.push('p = ' + fmtNumber(Number(p.toPrecision(4))))
  if (typeof draw.u === 'number') parts.push('u = ' + fmtNumber(draw.u))
  return parts.length > 0 ? `${text} (reported: ${parts.join(', ')})` : `${text} (reported)`
}

/** Where a missed line-attack landed, as text, or null when the result has no point. */
function landedAt(result: unknown): string | null {
  const point = (result && typeof result === 'object' && 'point' in result) ? result.point : null
  if (point && typeof point === 'object' && 'x' in point && 'y' in point) {
    return `(${(point.x as number).toFixed(2)}, ${(point.y as number).toFixed(2)})`
  }
  return null
}

function hitText(result: unknown): string | null {
  return result && typeof result === 'object' && 'hit' in result ? `hit ${result.hit || 'unknown'}` : null
}

function describeLineAttack(e: LogEntry): string {
  const target = e.loggedEffect?.params?.target
  const result = e.result
  const at = `line-attack at ${target || 'unknown'}`
  const hit = hitText(result)
  if (hit !== null) return `${at} -> ${hit}`
  const landed = landedAt(result)
  return landed === null ? `${at} -> missed` : `${at} -> missed, landed at ${landed}`
}

function describeEffect(e: LogEntry): string {
  if (e.loggedEffect?.type === 'line-attack') return describeLineAttack(e)
  return e.loggedEffect?.type + (e.result !== undefined ? ' -> ' + JSON.stringify(e.result) : '')
}

// A channel moved on (a step part of the way): where it got to, and whether it was stopped there.
function describeProgress(e: LogEntry): string {
  const result = e.result as { at?: { x: number; y: number }; blocked?: boolean } | null | undefined
  const type = e.actionInProgress?.type ?? ''
  if (!result?.at) return 'progress ' + type
  const where = `(${fmtNumber(result.at.x)}, ${fmtNumber(result.at.y)})`
  return `${type} at ${where}` + (result.blocked ? ', blocked' : '')
}

const DESCRIBERS: Record<string, (e: LogEntry) => string> = {
  'action-start': (e) => 'start ' + (e.actionInProgress?.type ?? ''),
  'action-progress': describeProgress,
  'action-end': (e) => 'end ' + (e.actionInProgress?.type ?? ''),
  queue: () => 'queue written',
  'action-cancelled': (e) => 'cancelled ' + (e.actionInProgress?.type ?? ''),
  effect: describeEffect,
  intent: describeIntent,
  snapshot: (e) => 'snapshot (' + e.changes.length + ' resources)',
}

export function describe(e: LogEntry): string {
  return Object.hasOwn(DESCRIBERS, e.kind) ? DESCRIBERS[e.kind]!(e) : e.kind
}

export function formatLogTime(t: number): string {
  return t.toFixed(2)
}