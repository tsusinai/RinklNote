package com.example.rinklnote.navigation

import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.ui.viewmodel.QuickAddState
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.today

/** 侧边栏进入完整编辑时只传递草稿；金额输入前不落库，也不丢弃已选元数据。 */
internal fun QuickAddState.toEditorDraft(): Bill = Bill(
    amountMinor = Money.parseMinor(amount) ?: 0L,
    billType = billType,
    categoryId = selectedCategory?.id ?: 0L,
    categoryName = selectedCategory?.name.orEmpty(),
    subCategoryName = selectedSubCategory?.name,
    accountId = selectedAccount?.id ?: 0L,
    remark = remark.ifBlank { null },
    date = (dateOverride ?: today()).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli(),
    latitude = location?.latitude,
    longitude = location?.longitude
)
