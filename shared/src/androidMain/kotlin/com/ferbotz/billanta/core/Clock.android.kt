package com.ferbotz.billanta.core

actual fun systemEpochMillis(): Long = System.currentTimeMillis()

actual fun localUtcOffsetMillis(epochMillis: Long): Long =
    java.util.TimeZone.getDefault().getOffset(epochMillis).toLong()
