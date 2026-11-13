import type { LogDraw, LogEntry } from './types.ts'

export function fmtNumber(n: number): string {
  return Number.isInteger(n) ? n.toFixed(1) : String(n)
}

export function fmtValue(v: unknown): string {
  if (typeof v === 'number') {
    return fmtNumber(v)
  }
  if (typeof v === 'boolean' || v === null || v === undefined) {
    return String(v)
  }
  if (typeof v === 'object' && v !== null && 'min' in v && 'max' in v) {
    return `[${fmtNumber((v as { min: number }).min)}, ${fmtNumber((v as { max: number }).max)})`
  }
  if (Array.isArray(v)) {
    return `[${v.map(fmtValue).join(', ')}]`
  }
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

export function entityOf(e: LogEntry): string|null {
  if (e.resource) return e.resource
  if (e.effect?.params?.target && typeof e.effect.params.target === 'string') return e.effect.params.target
  const firstEntityChange = (e.changes ?? []).find(c => c.type === 'entity')
  if (firstEntityChange) return firstEntityChange.id
  if (e.effect?.params?.source && typeof e.effect.params.source === 'string') return e.effect.params.source
  return null
}

export function describe(e: LogEntry): string {
  switch (e.kind) {
    case 'action-start':
      return 'start ' + (e.action?.type ?? '')
    case 'action-end':
      return 'end ' + (e.action?.type ?? '')
    case 'action-cancelled':
      return 'cancelled ' + (e.action?.type ?? '')
    case 'effect':
      if (e.effect?.type === 'line-attack') {
        const params = e.effect.params || {};
        const target = params.target;
        const result = e.result;

        if (result && typeof result === 'object' && 'hit' in result) {
          // Hit case
          return `line-attack at ${target || 'unknown'} -> hit ${result.hit || 'unknown'}`
        } else {
          // Miss case - show coordinates to 2 decimals
          const point = (result && typeof result === 'object' && 'point' in result) ? result.point : null;
          if (point && typeof point === 'object' && 'x' in point && 'y' in point) {
            return `line-attack at ${target || 'unknown'} -> missed, landed at (${(point.x as number).toFixed(2)}, ${(point.y as number).toFixed(2)})`
          }
          return `line-attack at ${target || 'unknown'} -> missed`
        }
      }
      return e.effect?.type + (e.result !== undefined ? ' -> ' + JSON.stringify(e.result) : '')
    case 'clock-anchored':
      return 'clock anchored (' + e.reason + ')'
    case 'snapshot':
      return 'snapshot (' + e.changes.length + ' resources)'
    default:
      return e.kind
  }
}

export function formatLogTime(t: number): string {
  return t.toFixed(2)
}