package com.ferbotz.billanta.core

/** Wall-clock time. Injected so sync timestamps (last-write-wins) are testable. */
fun interface EpochClock {
    fun nowMillis(): Long
}

expect fun systemEpochMillis(): Long

val SystemClock: EpochClock = EpochClock { systemEpochMillis() }
