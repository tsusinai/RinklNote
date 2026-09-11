import { describe, it, expect } from 'vitest'
import type { Bill, SyncResponse, Account, Category, MeResponse, Budget } from '../../types'

describe('types', () => {
  it('Bill exposes sync-relevant fields (used by fetchAllBills)', () => {
    const b: Bill = {
      id: 1, amountMinor: 1990, billType: 'EXPENSE', categoryId: 1, categoryName: '三餐',
      subCategoryName: null, accountId: 1, remark: null, date: 1700000000000,
      source: 'WEB', createdAt: 1700000000000, updatedAt: 1700000000000, deleted: false,
    }
    expect(b.updatedAt).toBeTypeOf('number')
  })
  it('SyncResponse has composite cursor fields', () => {
    const r: SyncResponse = { bills: [], serverTime: 0, hasMore: false, nextAfter: null, nextAfterId: null }
    expect(r.nextAfterId).toBeNull()
  })
  it('Account has asset-plan optional fields', () => {
    const a: Account = { id: 1, name: '微信', balanceMinor: 0, iconColor: '#28C145', updatedAt: 0, deleted: false, type: 'WECHAT', isLiability: false }
    expect(a.isLiability).toBe(false)
  })
  // 其余为纯类型占位，引用即校验
  it('other interfaces are constructible', () => {
    const c: Category = { id: 1, name: '三餐', iconName: 'food', type: 'EXPENSE', subCategories: [] }
    const m: MeResponse = { id: 1, phone: null, qqNumber: null, qqOpenid: null, createdAt: null, aiDisabled: false }
    const bu: Budget = { id: 1, monthStart: 1700000000000, amountMinor: 200000, createdAt: 0, updatedAt: null, deleted: false }
    expect(c.type).toBe('EXPENSE'); expect(m.aiDisabled).toBe(false); expect(bu.amountMinor).toBe(200000)
  })
})
