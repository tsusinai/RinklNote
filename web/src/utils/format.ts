export function formatMoney(n: number): string {
  const neg = n < 0
  const abs = Math.abs(n).toFixed(2)
  const [int, dec] = abs.split('.')
  const withSep = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (neg ? '-' : '') + withSep + '.' + dec
}

export function formatSigned(n: number): string {
  const s = formatMoney(Math.abs(n))
  return n < 0 ? '-' + s : '+' + s
}
