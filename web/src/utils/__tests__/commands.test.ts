import { describe, it, expect } from 'vitest'
import { COMMAND_ACTIONS, filterActions, nextIndex } from '../commands'

/* 命令面板配置数组单测（Task 3.4）：过滤匹配与选中游标回绕。 */

describe('COMMAND_ACTIONS 配置完整性', () => {
  it('每条路由类动作都带 route 名；id 唯一', () => {
    const ids = new Set<string>()
    for (const a of COMMAND_ACTIONS) {
      expect(ids.has(a.id)).toBe(false)
      ids.add(a.id)
      if (a.type === 'route') expect(a.route).toBeTruthy()
    }
  })
})

describe('filterActions 过滤', () => {
  it('空查询返回全部', () => {
    expect(filterActions('  ')).toHaveLength(COMMAND_ACTIONS.length)
  })
  it('按 label 匹配（大小写不敏感）', () => {
    const r = filterActions('图表')
    expect(r).toHaveLength(1)
    expect(r[0].id).toBe('charts')
    expect(filterActions('csv')[0].id).toBe('export-csv')
  })
  it('按 keywords 辅助词匹配', () => {
    expect(filterActions('search').map((a) => a.id)).toContain('bills')
  })
  it('无匹配返回空数组', () => {
    expect(filterActions('不存在的命令xyz')).toEqual([])
  })
})

describe('nextIndex 选中游标', () => {
  it('正向步进与环形回绕', () => {
    expect(nextIndex(0, 1, 3)).toBe(1)
    expect(nextIndex(2, 1, 3)).toBe(0) // 末尾回绕到首位
    expect(nextIndex(2, 4, 3)).toBe(0) // 多步回绕
  })
  it('负向步进与环形回绕', () => {
    expect(nextIndex(0, -1, 3)).toBe(2) // 首位回绕到末尾
    expect(nextIndex(1, -1, 3)).toBe(0)
  })
  it('空列表返回 -1', () => {
    expect(nextIndex(0, 1, 0)).toBe(-1)
  })
})
