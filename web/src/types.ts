export type MoneyStyle = 'EXPENSE' | 'INCOME'
export type BillSource = 'WEB' | 'APP' | 'QQ'
export type AccountType =
  | 'CASH' | 'BANK_CARD' | 'WECHAT' | 'ALIPAY' | 'VIRTUAL' | 'OTHER' | 'CREDIT' | 'LOAN'

export interface SubCategory { id: number; name: string; parentCategoryId: number }

export interface Category {
  id: number; name: string; iconName: string; type: MoneyStyle;
  subCategories: SubCategory[]; // wire `billType` maps to this `type` field via alias below
}

export interface Account {
  id: number; name: string; balance: number; iconColor: string;
  updatedAt: number; deleted: boolean;
  // 资产 Plan（worktree 未合并）：将来 server 会补下列可选字段，先预留
  type?: AccountType; isLiability?: boolean; openingBalance?: number;
}

export interface Bill {
  id: number; amount: number; billType: MoneyStyle;
  categoryId: number; categoryName: string; subCategoryName: string | null;
  accountId: number; remark: string | null; date: number; source: BillSource | string;
  createdAt: number; updatedAt: number | null; deleted: boolean;
}

export interface SyncResponse {
  bills: Bill[]; serverTime: number; hasMore: boolean;
  nextAfter: number | null; nextAfterId: number | null;
}

export interface Budget {
  id: number; monthStart: number; amount: number; createdAt: number;
  updatedAt: number | null; deleted: boolean;
}

export interface AuthResponse { userId: number; token: string }
export interface MessageResponse { message: string }

export interface MeResponse {
  id: number; phone: string | null; qqNumber: string | null;
  qqOpenid: string | null; createdAt: string | null; aiDisabled: boolean;
}

// ── 控制台扩展类型（原 web 全量 1:1）──
export interface Keyword { id: number; keyword: string; categoryName: string; priority: number }
export interface Template { id: number; label: string; amount: number; categoryId: number; categoryName: string; accountId: number; sortOrder: number }
export interface MonthlyReviewSpike { date: string; amount: number; ratioPct: number }
export interface MonthlyReviewTopCategory { name: string; amount: number }
export interface MonthlyReview {
  summary: string; totalExpense: number; totalIncome: number; highlights: string[];
  spikeDays: MonthlyReviewSpike[]; biggestSingle: { categoryName: string; amount: number; date: string } | null;
  topCategories: MonthlyReviewTopCategory[];
}
export interface SuggestConfig { enabled: boolean; lookbackDays: number; minOccurrences: number; displayDuration: number }
export interface QqBotStatus { configured: string; maskedAppId: string }
export interface QqBotBindStatus { bound: string; openid: string }
