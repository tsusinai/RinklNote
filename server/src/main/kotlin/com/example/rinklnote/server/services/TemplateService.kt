package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BillTemplatesTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class TemplateDTO(
    val id: Long = 0, val label: String, val amount: Double,
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
                TemplateDTO(
                    id = it[BillTemplatesTable.id], label = it[BillTemplatesTable.label],
                    amount = it[BillTemplatesTable.amount], categoryId = it[BillTemplatesTable.categoryId],
                    categoryName = it[BillTemplatesTable.categoryName],
                    subCategoryName = it[BillTemplatesTable.subCategoryName],
                    accountId = it[BillTemplatesTable.accountId], sortOrder = it[BillTemplatesTable.sortOrder]
                )
            }
    }

    fun create(userId: Long, dto: TemplateDTO): TemplateDTO {
        val now = System.currentTimeMillis()
        val id = transaction {
            BillTemplatesTable.insert {
                it[BillTemplatesTable.userId] = userId
                it[label] = dto.label; it[amount] = dto.amount
                it[categoryId] = dto.categoryId; it[categoryName] = dto.categoryName
                it[subCategoryName] = dto.subCategoryName; it[accountId] = dto.accountId
                it[sortOrder] = dto.sortOrder
                it[createdAt] = now; it[updatedAt] = now
            } get BillTemplatesTable.id
        }
        return dto.copy(id = id)
    }

    fun update(userId: Long, templateId: Long, dto: TemplateDTO): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            BillTemplatesTable.update({
                (BillTemplatesTable.id eq templateId) and (BillTemplatesTable.userId eq userId)
            }) {
                it[label] = dto.label; it[amount] = dto.amount
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
