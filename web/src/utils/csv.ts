export function csvSafe(v: string | number | null | undefined): string {
  const s = v == null ? '' : String(v)
  if (/^[=+\-@\t\r]/.test(s)) return "'" + s
  return s
}

export function toCsv(rows: (string | number | null | undefined)[][]): string {
  return rows.map((r) => r.map(csvSafe).join(',')).join('\r\n')
}
