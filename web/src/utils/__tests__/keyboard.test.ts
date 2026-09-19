import { describe, it, expect } from 'vitest'
import { isEditableTarget, appendAmountKey, isComposingKeyEvent } from '../keyboard'

/* 键盘流纯函数单测（Task 3.4）：可编辑元素守卫 + 金额直输合键规则 + IME 组合期识别。 */

describe('isComposingKeyEvent IME 组合期识别', () => {
  it('isComposing=true（Chrome/标准口径）识别为组合期按键', () => {
    expect(isComposingKeyEvent({ isComposing: true })).toBe(true)
  })
  it('keyCode=229（Safari/旧口径）识别为组合期按键', () => {
    expect(isComposingKeyEvent({ keyCode: 229 })).toBe(true)
  })
  it('普通按键（含 Enter/Esc/数字）不误判', () => {
    expect(isComposingKeyEvent({ isComposing: false, keyCode: 13 })).toBe(false)
    expect(isComposingKeyEvent({})).toBe(false)
    expect(isComposingKeyEvent({ keyCode: 69 })).toBe(false)
  })
})

describe('isEditableTarget 可编辑元素守卫', () => {
  it('input / textarea / select 视为可编辑', () => {
    expect(isEditableTarget(document.createElement('input'))).toBe(true)
    expect(isEditableTarget(document.createElement('textarea'))).toBe(true)
    expect(isEditableTarget(document.createElement('select'))).toBe(true)
  })
  it('contenteditable 元素视为可编辑', () => {
    const div = document.createElement('div')
    div.setAttribute('contenteditable', 'true')
    expect(isEditableTarget(div)).toBe(true)
  })
  it('普通元素与空目标不可编辑（快捷键放行）', () => {
    expect(isEditableTarget(document.createElement('div'))).toBe(false)
    expect(isEditableTarget(document.body)).toBe(false)
    expect(isEditableTarget(null)).toBe(false)
  })
})

describe('appendAmountKey 金额直输', () => {
  it('数字追加：空串与已有金额', () => {
    expect(appendAmountKey('', '5')).toBe('5')
    expect(appendAmountKey('12', '3')).toBe('123')
    expect(appendAmountKey('12.3', '4')).toBe('12.34')
  })
  it('小数位 ≤2：第三位小数被拒（返回 null）', () => {
    expect(appendAmountKey('12.34', '5')).toBeNull()
  })
  it('整数部分 ≤8 位：第 9 位被拒；小数位追加不受整数上限影响', () => {
    expect(appendAmountKey('12345678', '9')).toBeNull()
    expect(appendAmountKey('1234567.8', '9')).toBe('1234567.89') // 追加到小数位，合法
    expect(appendAmountKey('1234567', '8')).toBe('12345678')
  })
  it('小数点：空串补全 0.，已有小数点时拒绝', () => {
    expect(appendAmountKey('', '.')).toBe('0.')
    expect(appendAmountKey('12', '.')).toBe('12.')
    expect(appendAmountKey('12.', '.')).toBeNull()
  })
  it('Backspace 删除末位；空串仍为空串', () => {
    expect(appendAmountKey('123', 'Backspace')).toBe('12')
    expect(appendAmountKey('', 'Backspace')).toBe('')
  })
  it('其余按键一律不处理（返回 null）', () => {
    expect(appendAmountKey('12', 'a')).toBeNull()
    expect(appendAmountKey('12', 'Enter')).toBeNull()
    expect(appendAmountKey('12', '-')).toBeNull()
    expect(appendAmountKey('12', 'Tab')).toBeNull()
  })
})
