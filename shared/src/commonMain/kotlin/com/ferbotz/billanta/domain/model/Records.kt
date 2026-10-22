package com.ferbotz.billanta.domain.model

/** The signed-in user (`GET /users/me`). */
data class UserAccount(
    val id: String,
    val email: String,
    val name: String? = null,
    val photoUrl: String? = null,
    val isPremium: Boolean = false,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
)

/** Wire names are exact (`"Draft" | "Pending" | "Paid"`). */
enum class InvoiceDocStatus { Draft, Pending, Paid }

data class TemplateInfo(
    val id: String,
    val name: String,
    val category: String? = null,
    val thumbnailUrl: String? = null,
    val isPremium: Boolean = false,
    val currentVersion: Long = 1,
    val checksum: String = "",
)

/** A cached compiled render tree (Billanta Template JSON) — immutable per (templateId, version). */
data class CompiledTemplate(
    val templateId: String,
    val version: Long,
    val checksum: String,
    /** The raw Template JSON document; the renderer parses it. */
    val json: String,
)
