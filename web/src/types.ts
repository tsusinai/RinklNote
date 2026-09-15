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
  id: number; name: string; balanceMinor: number; iconColor: string; iconKey?: string;
  updatedAt: number; deleted: boolean;
  // 资产 Plan（worktree 未合并）：将来 server 会补下列可选字段，先预留
  type?: AccountType; isLiability?: boolean; openingBalanceMinor?: number;
}

export interface Bill {
  id: number; amountMinor: number; billType: MoneyStyle;
  categoryId: number; categoryName: string; subCategoryName: string | null;
  accountId: number; remark: string | null; date: number; source: BillSource | string;
  createdAt: number; updatedAt: number | null; deleted: boolean;
}

export interface SyncResponse {
  bills: Bill[]; serverTime: number; hasMore: boolean;
  nextAfter: number | null; nextAfterId: number | null;
}

export interface Budget {
  id: number; monthStart: number; amountMinor: number; createdAt: number;
  updatedAt: number | null; deleted: boolean;
}

export interface AuthResponse { userId: number; token: string }
export interface MessageResponse { message: string }

export interface MeResponse {
  id: number; phone: string | null; qqNumber: string | null;
  qqOpenid: string | null; createdAt: string | null; aiDisabled: boolean;
}

// ── 控制台扩展类型（原 web 全量 1:1）──
// 金额字段统一为「分」整数：amountMinor / balanceMinor / totalXxxMinor / expenseMinor …
export interface Keyword { id: number; keyword: string; categoryName: string; priority: number }
export interface Template { id: number; label: string; amountMinor: number; categoryId: number; categoryName: string; accountId: number; sortOrder: number }
export interface MonthlyReviewSpike { date: string; amountMinor: number; ratioPct: number }
export interface MonthlyReviewTopCategory { name: string; amountMinor: number }
export interface MonthlyReview {
  summary: string; totalExpenseMinor: number; totalIncomeMinor: number; highlights: string[];
  spikeDays: MonthlyReviewSpike[]; biggestSingle: { categoryName: string; amountMinor: number; date: string } | null;
  topCategories: MonthlyReviewTopCategory[];
}
export interface SuggestConfig { enabled: boolean; lookbackDays: number; minOccurrences: number; displayDuration: number }
// ── 多通道机器人（QQ / 飞书 / 企业微信）管理面：三通道端点同构，字段随通道差异 ──
export type BotChannel = 'qq' | 'feishu' | 'wecom'
export interface BotChannelStatus { configured: string; maskedAppId?: string; maskedToken?: string; message?: string }
export interface BotChannelConfig {
  configured: string; maskedAppId?: string; maskedToken?: string
  hasSaved?: string; hasEncryptKey?: string; hasPushWebhook?: string
}
export interface BotChannelBindStatus { bound: string; openid?: string; openId?: string; message?: string }
