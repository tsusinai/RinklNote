import { defineStore } from 'pinia'

const KEY = 'rkl_theme'
export type ThemeMode = 'system' | 'light' | 'dark'

function apply(mode: ThemeMode) {
  const el = document.documentElement
  if (mode === 'light') el.dataset.theme = 'light'
  else if (mode === 'dark') el.dataset.theme = 'dark'
  else delete el.dataset.theme
}

export const useThemeStore = defineStore('theme', {
  state: () => ({ theme: (localStorage.getItem(KEY) as ThemeMode) || 'system' }),
  actions: {
    set(mode: ThemeMode) { this.theme = mode; localStorage.setItem(KEY, mode); apply(mode) },
    toggle() { this.set(this.theme === 'dark' ? 'light' : 'dark') },
    init() { apply(this.theme) },
  },
})
