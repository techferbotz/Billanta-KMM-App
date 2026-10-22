package com.ferbotz.billanta.core

/** Wall-clock time. Injected so sync timestamps (last-write-wins) are testable. */
fun interface EpochClock {
    fun nowMillis(): Long
}

expect fun systemEpochMillis(): Long

val SystemClock: EpochClock = EpochClock { systemEpochMillis() }

/** How far the device's time zone is ahead of UTC at [epochMillis], daylight saving included. */
expect fun localUtcOffsetMillis(epochMillis: Long): Long

/**
 * Today's date on the device, as `YYYY-MM-DD`. Invoice dates are calendar dates the user sees, so
 * an invoice made at 1 a.m. in India must not be dated yesterday, which is what UTC would say.
 */
fun EpochClock.todayIso(): String {
    val now = nowMillis()
    return Iso8601.formatDate(now + localUtcOffsetMillis(now))
}
