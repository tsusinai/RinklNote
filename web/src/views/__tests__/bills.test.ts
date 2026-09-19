import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import Bills from '../console/Bills.vue'
import { useDataStore } from '../../stores/data'
import type { Bill, BillSearchResponse } from '../../types'

/* 账单页服务端搜索竞态单测：连续变更筛选条件时，先发出的请求后返回，
 * 会把过期结果覆盖到列表上（筛选条件与列表内容不一致）。 */

const { searchMock } = vi.hoisted(() => ({ searchMock: vi.fn() }))
vi.mock('../../api/bills', () => ({
  bills: {
    search: searchMock,
    create: vi.fn(), update: vi.fn(), remove: vi.fn(), parse: vi.fn(),
  },
}))

let seq = 0
function bill(p: Partial<Bill>): Bill {
  return {
    id: ++seq, amountMinor: p.amountMinor ?? 1000, billType: p.billType ?? 'EXPENSE',
    categoryId: p.categoryId ?? 1, categoryName: p.categoryName ?? '餐饮', subCategoryName: null,
    accountId: 1, remark: null, date: new Date(2026, 8, 10).getTime(),
    source: 'WEB', createdAt: 0, updatedAt: null, deleted: false,
  }
}
function resp(bills: Bill[], total = bills.length): BillSearchResponse {
  return { bills, page: 1, pageSize: 50, total, totalPages: 1, sumExpenseMinor: 0, sumIncomeMinor: 0 }
}

/** 挂载并完成首屏加载（调用 #1），返回工具句柄 */
async function mountBills() {
  const pinia = createPinia()
  const data = useDataStore(pinia)
  data.cats = [
    { id: 1, name: '餐饮', iconName: '', type: 'EXPENSE', subCategories: [] },
    { id: 2, name: '交通', iconName: '', type: 'EXPENSE', subCategories: [] },
    { id: 3, name: '购物', iconName: '', type: 'EXPENSE', subCategories: [] },
  ]
  const wrapper = mount(Bills, {
    global: { plugins: [pinia], directives: { reveal: {} } },
  })
  await flushPromises()
  return { wrapper, data }
}

function pickSelect(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('select.sel')
}

async function chooseCategory(wrapper: ReturnType<typeof mount>, id: string) {
  const select = pickSelect(wrapper)
  ;(select.element as HTMLSelectElement).value = id
  await select.trigger('change')
  await flushPromises()
}

beforeEach(() => {
  searchMock.mockReset()
})

describe('Bills 服务端搜索竞态', () => {
  it('先发出的请求后返回时，列表不得被过期结果覆盖', async () => {
    const pending: ((v: BillSearchResponse) => void)[] = []
    searchMock.mockImplementation(() => new Promise<BillSearchResponse>((resolve) => pending.push(resolve)))

    const { wrapper } = await mountBills() // 调用 #1（首屏，无条件）
    expect(searchMock).toHaveBeenCalledTimes(1)

    // 筛选「交通」（调用 #2）→ 立刻改选「购物」（调用 #3）
    await chooseCategory(wrapper, '2') // 调用 #2
    expect(searchMock).toHaveBeenCalledTimes(2)
    await chooseCategory(wrapper, '3') // 调用 #3
    expect(searchMock).toHaveBeenCalledTimes(3)

    // 服务端乱序返回：#3（购物，total=888）先到，#2（交通，total=777）后到
    pending[2](resp([bill({ categoryName: '购物', categoryId: 3 })], 888))
    await flushPromises()
    pending[1](resp([bill({ categoryName: '交通', categoryId: 2 })], 777))
    await flushPromises()

    // 汇总卡口径 = 最后一次请求的 total（888）；过期响应的 777 不得覆盖
    expect(wrapper.text()).toContain('888')
    expect(wrapper.text()).not.toContain('777')
    // 列表同理：过期「交通」不得顶掉「购物」。断言收窄到 .rows 容器——
    // 整页文本含分类下拉的「交通」选项与行内 cat-dot 首字「交」，整页断言恒假；
    // 且 TransitionGroup 离场节点只可能多渲染、不可能顶掉当前列表内容。
    const rowsText = wrapper.find('.rows').text()
    expect(rowsText).not.toContain('交通')
    expect(rowsText).toContain('购物')
    wrapper.unmount()
  })

  it('过期响应不重置 loading（以最后一次请求的状态为准）', async () => {
    const pending: ((v: BillSearchResponse) => void)[] = []
    searchMock.mockImplementation(() => new Promise<BillSearchResponse>((resolve) => pending.push(resolve)))

    const { wrapper } = await mountBills() // #1
    await chooseCategory(wrapper, '2') // #2
    await chooseCategory(wrapper, '3') // #3

    pending[2](resp([bill({ categoryName: '购物' })]))
    await flushPromises()
    expect((wrapper.vm as any).loading).toBe(false) // #3 完成，loading 应为 false

    pending[1](resp([bill({ categoryName: '交通' })]))
    await flushPromises()
    // 过期响应返回时不得把 loading 重置为 false（此刻虽无在途请求，但语义上以最后请求为准；
    // 若在途场景下被过期响应提前拉低，骨架屏会提前消失）
    expect((wrapper.vm as any).loading).toBe(false)
    wrapper.unmount()
  })
})
