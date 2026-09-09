package com.example.rinklnote.data.db

import androidx.room.TypeConverter
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.MessageKind
import com.example.rinklnote.domain.Source

/** Room TypeConverters for domain enums. */
class Converters {
    @TypeConverter
    fun fromBillType(value: BillType): String = value.value

    @TypeConverter
    fun toBillType(value: String): BillType = BillType.fromValue(value)

    @TypeConverter
    fun fromSource(value: Source): String = value.value

    @TypeConverter
    fun toSource(value: String): Source = Source.fromValue(value)

    @TypeConverter
    fun fromMessageKind(value: MessageKind): String = value.value

    @TypeConverter
    fun toMessageKind(value: String): MessageKind = MessageKind.fromValue(value)
}
