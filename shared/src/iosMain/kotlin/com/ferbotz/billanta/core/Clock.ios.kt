package com.ferbotz.billanta.core

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun systemEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
