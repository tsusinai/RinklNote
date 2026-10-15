package com.example.rinklnote.server.services.bot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class InteractionBill(
    val id: Long, val amountMinor: Long, val categoryId: Long, val categoryName: String,
    val billType: String, val date: Long, val accountName: String, val updatedAt: Long
)

@Serializable
data class InteractionCategory(val id: Long, val name: String)

@Serializable
data class InteractionBudget(
    val budgetMinor: Long?, val spentMinor: Long, val remainingMinor: Long?,
    val enabled: Boolean, val hour: Int, val minute: Int
)

@Serializable
data class InteractionView(
    val kind: String, val title: String, val text: String, val expiresAt: Long,
    val consumed: Boolean = false, val message: String? = null,
    val bill: InteractionBill? = null, val categories: List<InteractionCategory> = emptyList(),
    val budget: InteractionBudget? = null
)

@Serializable
data class InteractionEdit(
    val operation: String, val amountMinor: Long? = null, val categoryId: Long? = null,
    val enabled: Boolean? = null, val hour: Int? = null, val minute: Int? = null
)

data class BotButton(val label: String, val token: String)
data class BotCard(val title: String, val text: String, val buttons: List<BotButton>, val actor: String)
data class BotActionOutcome(val text: String, val card: JsonObject? = null, val qqCard: BotCard? = null)
class BotActionException(val status: Int, override val message: String) : RuntimeException(message)
