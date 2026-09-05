import { describe, it, expect } from 'vitest'
import { csvSafe, toCsv } from '../csv'

describe('csv', () => {
  it('prefixes formula-injection cells', () => {
    expect(csvSafe('=SUM(A1)')).toBe("'=SUM(A1)")
    expect(csvSafe('+1')).toBe("'+1")
    expect(csvSafe('hello')).toBe('hello')
  })
  it('joins rows with CRLF', () => {
    expect(toCsv([['a', 'b'], ['c', 'd']])).toBe('a,b\r\nc,d')
  })
})
