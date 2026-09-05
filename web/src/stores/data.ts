import { defineStore } from 'pinia'
import { categories } from '../api/categories'
import { accounts } from '../api/accounts'
import { fetchAllBills } from '../api/bills'
import { keywords } from '../api/keywords'
import { useAuthStore } from './auth'
import type { Bill, Category, Account, Keyword } from '../types'

export const useDataStore = defineStore('data', {
  state: () => ({
    bills: [] as Bill[],
    cats: [] as Category[],
    accts: [] as Account[],
    keywords: [] as Keyword[],
    lastSync: 0,
  }),
  actions: {
    async loadData() {
      const [cats, accts] = await Promise.all([
        categories.list().catch(() => [] as Category[]),
        accounts.list().catch(() => [] as Account[]),
      ])
      this.cats = cats
      this.accts = accts
      const auth = useAuthStore()
      if (auth.token) {
        const all = await fetchAllBills()
        this.bills = all.reverse() // newest first（与原 web 一致）
        this.lastSync = Date.now()
        await this.loadKeywords()
      }
    },
    async refreshAccounts() {
      try { this.accts = await accounts.list() } catch { /* 保留旧数据 */ }
    },
    async loadKeywords() {
      if (!useAuthStore().token) { this.keywords = []; return }
      try { this.keywords = await keywords.list() } catch { /* 保留旧数据 */ }
    },
  },
})
