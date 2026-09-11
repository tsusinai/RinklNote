package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BillTemplatesTable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TemplateDTO(
    val id: Long = 0, val label: String,
    // 新字段（分，权威值）；旧客户端只发 amount 时回退。
    val amountMinor: Long? = null,
    // 旧字段（元），仅供旧客户端；值 = amountMinor 换算。
    // @EncodeDefault 必需：Json 默认 encodeDefaults=false，否则该字段会被整个省略。
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val amount: Double? = null,
    val categoryId: Long, val categoryName: String,
    val subCategoryName: String? = null, val accountId: Long,
    val sortOrder: Int = 0
)

class TemplateService {
    fun list(userId: Long): List<TemplateDTO> = transaction {
        BillTemplatesTable.selectAll()
            .where { BillTemplatesTable.userId eq userId }
            .orderBy(BillTemplatesTable.sortOrder)
            .map {
                val minor = it[BillTemplatesTable.amountMinor] ?: 0L
                TemplateDTO(
                    id = it[BillTemplatesTable.id], label = it[BillTemplatesTable.label],
                    amountMinor = minor, amount = Money.fromMinor(minor),
                    categoryId = it[BillTemplatesTable.categoryId],
                    categoryName = it[BillTemplatesTable.categoryName],
                    subCategoryName = it[BillTemplatesTable.subCategoryName],
                    accountId = it[BillTemplatesTable.accountId], sortOrder = it[BillTemplatesTable.sortOrder]
                )
            }
    }

    fun create(userId: Long, dto: TemplateDTO): TemplateDTO {
        val now = System.currentTimeMillis()
        val minor = Money.resolveAmountMinor(dto.amountMinor, dto.amount)
        val id = transaction {
            BillTemplatesTable.insert {
                it[BillTemplatesTable.userId] = userId
                it[label] = dto.label; it[amountMinor] = minor; it[amount] = Money.fromMinor(minor)
                it[categoryId] = dto.categoryId; it[categoryName] = dto.categoryName
                it[subCategoryName] = dto.subCategoryName; it[accountId] = dto.accountId
                it[sortOrder] = dto.sortOrder
                it[createdAt] = now; it[updatedAt] = now
            } get BillTemplatesTable.id
        }
        return TemplateDTO(
            id = id, label = dto.label, amountMinor = minor, amount = Money.fromMinor(minor),
            categoryId = dto.categoryId, categoryName = dto.categoryName,
            subCategoryName = dto.subCategoryName, accountId = dto.accountId, sortOrder = dto.sortOrder
        )
    }

    fun update(userId: Long, templateId: Long, dto: TemplateDTO): Boolean {
        val now = System.currentTimeMillis()
        val minor = Money.resolveAmountMinor(dto.amountMinor, dto.amount)
        return transaction {
            BillTemplatesTable.update({
                (BillTemplatesTable.id eq templateId) and (BillTemplatesTable.userId eq userId)
            }) {
                it[label] = dto.label; it[amountMinor] = minor; it[amount] = Money.fromMinor(minor)
                it[categoryId] = dto.categoryId; it[categoryName] = dto.categoryName
                it[subCategoryName] = dto.subCategoryName; it[accountId] = dto.accountId
                it[updatedAt] = now
            }
        } > 0
    }

    fun delete(userId: Long, templateId: Long): Boolean = transaction {
        val exists = BillTemplatesTable.selectAll()
            .where { (BillTemplatesTable.id eq templateId) and (BillTemplatesTable.userId eq userId) }
            .singleOrNull() != null
        if (exists) exec("DELETE FROM bill_templates WHERE id = $templateId AND user_id = $userId")
        exists
    }

    fun reorder(userId: Long, ids: List<Long>) = transaction {
        ids.forEachIndexed { index, id ->
            BillTemplatesTable.update({
                (BillTemplatesTable.id eq id) and (BillTemplatesTable.userId eq userId)
            }) { it[sortOrder] = index }
        }
    }
}
