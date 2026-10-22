package com.ferbotz.billanta.core

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun systemEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun localUtcOffsetMillis(epochMillis: Long): Long =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)) * 1000L
