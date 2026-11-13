import { describe, expect, it } from 'vitest'
import { fmtNumber, fmtValue, drawText, shortId, entityOf, describe as describeFn, formatLogTime } from './format.ts'

describe('fmtNumber', () => {
  it('formats integer values with one decimal', () => {
    expect(fmtNumber(1)).toBe('1.0')
    expect(fmtNumber(4)).toBe('4.0')
  })

  it('formats non-integer values as strings', () => {
    expect(fmtNumber(3.71)).toBe('3.71')
    expect(fmtNumber(0.5)).toBe('0.5')
  })
})

describe('fmtValue', () => {
  it('formats numbers with fmtNumber', () => {
    expect(fmtValue(1)).toBe('1.0')
    expect(fmtValue(3.71)).toBe('3.71')
  })

  it('formats booleans and null/undefined as strings', () => {
    expect(fmtValue(true)).toBe('true')
    expect(fmtValue(false)).toBe('false')
    expect(fmtValue(null)).toBe('null')
    expect(fmtValue(undefined)).toBe('undefined')
  })

  it('formats objects with min/max as interval notation', () => {
    expect(fmtValue({ min: 0.5, max: 4.0 })).toBe('[0.5, 4.0)')
  })

  it('formats arrays by mapping fmtValue to each element', () => {
    expect(fmtValue([1, 2, 3])).toBe('[1.0, 2.0, 3.0]')
  })

  it('formats other objects as JSON strings', () => {
    expect(fmtValue({ a: 1 })).toBe('{"a":1}')
  })
})

describe('drawText', () => {
  it('formats a draw with for, dist and other properties', () => {
    const draw = {
      for: 'damage',
      dist: 'gaussian',
      mean: 2.5,
      sd: 1.0,
      truncate: { min: 0.5, max: 4.0 },
      value: 2.5
    }
    expect(drawText(draw)).toBe('damage: gaussian mean=2.5 sd=1.0 truncate=[0.5, 4.0) -> value=2.5')
  })

  it('formats a draw with different properties', () => {
    const draw = {
      for: 'break',
      dist: 'bernoulli',
      p: 0.5,
      u: 0.9,
      value: false
    }
    expect(drawText(draw)).toBe('break: bernoulli p=0.5 u=0.9 -> value=false')
  })

  it('formats a draw with categorical distribution', () => {
    const draw = {
      for: 'behaviour',
      dist: 'categorical',
      weights: [2.0, 1.0, 1.0],
      u: 0.61,
      value: 1
    }
    expect(drawText(draw)).toBe('behaviour: categorical weights=[2.0, 1.0, 1.0] u=0.61 -> value=1')
  })
})

describe('shortId', () => {
  it('formats an ID with prefix and suffix', () => {
    expect(shortId('entity:00000000-0000-4000-8000-000000000004')).toBe('entity:…0004')
  })
})

describe('entityOf', () => {
  it('returns resource when present', () => {
    const entry = { resource: 'entity:123' } as any
    expect(entityOf(entry)).toBe('entity:123')
  })

  it('returns effect target when present and is a string', () => {
    const entry = { effect: { params: { target: 'entity:456' } } } as any
    expect(entityOf(entry)).toBe('entity:456')
  })

  it('returns first entity change id when no resource or target', () => {
    const entry = { changes: [{ type: 'entity', id: 'entity:789' }] } as any
    expect(entityOf(entry)).toBe('entity:789')
  })

  it('returns effect source when present and is a string (fallback)', () => {
    const entry = { effect: { params: { source: 'entity:abc' } } } as any
    expect(entityOf(entry)).toBe('entity:abc')
  })

  it('returns null when no entity identifier found', () => {
    const entry = {} as any
    expect(entityOf(entry)).toBeNull()
  })
})

describe('describe', () => {
  it('describes action-start entries', () => {
    const entry = { kind: 'action-start', action: { type: 'move' } } as any
    expect(describeFn(entry)).toBe('start move')
  })

  it('describes action-end entries', () => {
    const entry = { kind: 'action-end', action: { type: 'move' } } as any
    expect(describeFn(entry)).toBe('end move')
  })

  it('describes action-cancelled entries', () => {
    const entry = { kind: 'action-cancelled', action: { type: 'move' } } as any
    expect(describeFn(entry)).toBe('cancelled move')
  })

  it('describes effect entries with result', () => {
    const entry = { kind: 'effect', effect: { type: 'damage' }, result: 5 } as any
    expect(describeFn(entry)).toBe('damage -> 5')
  })

  it('describes effect entries without result', () => {
    const entry = { kind: 'effect', effect: { type: 'damage' } } as any
    expect(describeFn(entry)).toBe('damage')
  })

  it('describes clock-anchored entries', () => {
    const entry = { kind: 'clock-anchored', reason: 'tick' } as any
    expect(describeFn(entry)).toBe('clock anchored (tick)')
  })

  it('describes snapshot entries', () => {
    const entry = { kind: 'snapshot', changes: [{ id: 'entity:123' }] } as any
    expect(describeFn(entry)).toBe('snapshot (1 resources)')
  })

  it('describes other kinds by their kind', () => {
    const entry = { kind: 'other-kind' } as any
    expect(describeFn(entry)).toBe('other-kind')
  })
})

describe('formatLogTime', () => {
  it('formats time with two decimal places', () => {
    expect(formatLogTime(123.456)).toBe('123.46')
    expect(formatLogTime(10.0)).toBe('10.00')
  })
})