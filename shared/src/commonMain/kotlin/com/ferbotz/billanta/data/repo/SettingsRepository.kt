package com.ferbotz.billanta.data.repo

import com.ferbotz.billanta.core.AppError
import com.ferbotz.billanta.core.AppResult
import com.ferbotz.billanta.core.DecimalString
import com.ferbotz.billanta.core.EpochClock
import com.ferbotz.billanta.core.asFailure
import com.ferbotz.billanta.core.asSuccess
import com.ferbotz.billanta.data.local.ProfileLocalDataSource
import com.ferbotz.billanta.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val local: ProfileLocalDataSource,
    private val clock: EpochClock,
    private val onLocalMutation: () -> Unit,
) {

    /** Defaults are served until a row exists (mirrors the server's auto-created defaults). */
    fun observeSettings(): Flow<UserSettings> = local.observeSettings().map { it ?: UserSettings() }

    suspend fun getSettings(): UserSettings = local.getSettings() ?: UserSettings()

    suspend fun save(settings: UserSettings): AppResult<UserSettings> {
        val tax = DecimalString.parseOrNull(settings.defaultTaxPercent)
        if (tax == null || tax.unscaled > 100L * tax.scaleDivisor) {
            return AppError.Validation("Default tax must be between 0 and 100").asFailure()
        }
        if (settings.nextInvoiceNumber < 1) {
            return AppError.Validation("Next invoice number must be at least 1").asFailure()
        }
        local.saveSettings(settings, dirty = true, updatedAtMillis = clock.nowMillis())
        onLocalMutation()
        return settings.asSuccess()
    }

    /** The number the next invoice should get, e.g. `INV-42`. */
    suspend fun peekNextInvoiceNumber(): String = getSettings().formatNextInvoiceNumber()

    /**
     * Called after an invoice is saved with the suggested number: advances the counter so two
     * drafts don't collide. No-op if the user typed their own number.
     */
    suspend fun consumeInvoiceNumberIfMatches(usedNumber: String) {
        val settings = getSettings()
        if (usedNumber == settings.formatNextInvoiceNumber()) {
            local.saveSettings(
                settings.copy(nextInvoiceNumber = settings.nextInvoiceNumber + 1),
                dirty = true,
                updatedAtMillis = clock.nowMillis(),
            )
            onLocalMutation()
        }
    }
}
