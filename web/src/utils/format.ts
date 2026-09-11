// 金额格式化现统一由 money.ts 提供（按「分」语义）。
// 此处仅做兼容再导出，避免散落的调用点反复改动。
export { formatMoney, formatMoneyPlain, formatSigned } from './money'
