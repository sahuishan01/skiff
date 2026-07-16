package com.transdecoder.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(status: TransferStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(value: String): TransferStatus {
        return TransferStatus.valueOf(value)
    }

    @TypeConverter
    fun fromDirection(direction: TransferDirection): String {
        return direction.name
    }

    @TypeConverter
    fun toDirection(value: String): TransferDirection {
        return TransferDirection.valueOf(value)
    }
}
