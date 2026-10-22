package com.ferbotz.billanta.data.local

import com.ferbotz.billanta.data.db.AccountRow
import com.ferbotz.billanta.data.db.CompiledTemplateRow
import com.ferbotz.billanta.data.db.TemplateRow
import com.ferbotz.billanta.domain.model.CompiledTemplate
import com.ferbotz.billanta.domain.model.TemplateInfo
import com.ferbotz.billanta.domain.model.UserAccount

internal fun Long.toBool(): Boolean = this != 0L
internal fun Boolean.toDbLong(): Long = if (this) 1L else 0L

internal fun AccountRow.toDomain() = UserAccount(
    id = id, email = email, name = name, photoUrl = photoUrl, isPremium = isPremium.toBool(),
    createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
)

internal fun TemplateRow.toDomain() = TemplateInfo(
    id = id, name = name, category = category, thumbnailUrl = thumbnailUrl,
    isPremium = isPremium.toBool(), currentVersion = currentVersion, checksum = checksum,
)

internal fun CompiledTemplateRow.toDomain() = CompiledTemplate(
    templateId = templateId, version = version, checksum = checksum, json = json,
)
