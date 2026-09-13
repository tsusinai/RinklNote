import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AccountIcon from '../AccountIcon.vue'
import { accountIconPath } from '../../utils/accountIcon'

describe('AccountIcon', () => {
  it('uses the name fallback for legacy accounts without iconKey', () => {
    const wrapper = mount(AccountIcon, { props: { name: '微信' } })
    expect(wrapper.get('path').attributes('d')).toBe(accountIconPath('WECHAT', '微信'))
  })

  it('falls back an unknown key to wallet', () => {
    const wrapper = mount(AccountIcon, { props: { name: '微信', iconKey: 'UNKNOWN' } })
    expect(wrapper.get('path').attributes('d')).toBe(accountIconPath('WALLET', '微信'))
  })

  it('renders different paths for different accounts', () => {
    const wechat = mount(AccountIcon, { props: { name: '微信', iconKey: 'WECHAT' } })
    const alipay = mount(AccountIcon, { props: { name: '支付宝', iconKey: 'ALIPAY' } })
    expect(wechat.get('path').attributes('d')).not.toBe(alipay.get('path').attributes('d'))
  })
})
