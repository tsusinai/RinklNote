package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 常去地点（Room v17 新增）：用户记账时**主动打点**的位置被异步累计合并而来。
 *
 * 隐私口径：不做后台定位、不反向地理编码（离线不出门）；数据只存在本机，
 * 不参与同步（无 server_id）。距离阈值内视为同一地点（合并更新 last_used /
 * 常用分类），阈值外新建一行。
 */
@androidx.compose.runtime.Immutable
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 地点名：无反向地理编码，落库时用分类名（如「三餐」）；仅本机展示。 */
    val name: String,
    /** 纬度（度，WGS-84，与 bills.latitude 同坐标系）。 */
    @ColumnInfo(name = "lat") val lat: Double,
    /** 经度（度，WGS-84）。 */
    @ColumnInfo(name = "lng") val lng: Double,
    /** 该地点最近一次记账用的分类（建议「常用分类」的来源）；可为空。 */
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    /** 最近一次在此记账的时间戳（合并刷新，用于「最近优先」排序）。 */
    @ColumnInfo(name = "last_used") val lastUsed: Long
)
