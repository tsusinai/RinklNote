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
