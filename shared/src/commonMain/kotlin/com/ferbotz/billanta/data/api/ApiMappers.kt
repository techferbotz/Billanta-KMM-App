package com.ferbotz.billanta.data.api

import com.ferbotz.billanta.core.Iso8601
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.domain.model.UserAccount

/*
 * The DTO ↔ domain mappings that are not values maps. Every record BE-021 reshaped (company,
 * settings, customers, products, invoices) is mapped in ValuesDtos.kt, without renaming a key.
 */

private fun isoToMillis(iso: String?): Long? = iso?.let { Iso8601.parseOrNull(it) }

// ---- user --------------------------------------------------------------------------------------

fun UserDto.toDomain() = UserAccount(
    id = id, email = email, name = name, photoUrl = photoUrl, isPremium = isPremium,
    createdAtMillis = isoToMillis(createdAt), updatedAtMillis = isoToMillis(updatedAt),
)

// ---- templates ---------------------------------------------------------------------------------

fun TemplateDto.toDomain() = TemplateInfo(
    id = id, name = name, category = category, thumbnailUrl = thumbnailUrl,
    isPremium = isPremium, currentVersion = currentVersion, checksum = checksum,
)
