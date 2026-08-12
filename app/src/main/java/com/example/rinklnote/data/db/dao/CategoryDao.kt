package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Query("SELECT * FROM categories WHERE bill_type = :billType ORDER BY id ASC")
    suspend fun getAllByType(billType: String): List<Category>

    @Query("SELECT * FROM sub_categories WHERE parent_category_id = :parentId ORDER BY id ASC")
    suspend fun getSubCategories(parentId: Long): List<SubCategory>

    @Query("SELECT * FROM sub_categories ORDER BY id ASC")
    suspend fun getAllSubCategories(): List<SubCategory>

    @Query("SELECT id FROM categories WHERE name = :name")
    suspend fun getIdByName(name: String): Long?

    @Insert
    suspend fun insertSubCategory(subCategory: SubCategory): Long

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}
