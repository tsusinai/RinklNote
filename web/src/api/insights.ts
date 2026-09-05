import { api } from './http'
import type { MonthlyReview, SuggestConfig } from '../types'

export const insights = {
  monthlyReview: (month: string) => api<MonthlyReview>(`/api/insights/monthly-review?month=${month}`),
  suggestConfig: () => api<SuggestConfig>('/api/insights/suggest-config'),
  saveSuggestConfig: (cfg: SuggestConfig) => api<SuggestConfig>('/api/insights/suggest-config', { method: 'PUT', body: cfg }),
}
