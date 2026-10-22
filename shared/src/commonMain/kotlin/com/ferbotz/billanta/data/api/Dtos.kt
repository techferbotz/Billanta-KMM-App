package com.ferbotz.billanta.data.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// ---- auth --------------------------------------------------------------------------------------

@Serializable
data class GoogleSignInRequest(val idToken: String)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
    /** Access-token lifetime in seconds (the server issues 900). */
    val expiresIn: Long = 900,
    val user: UserDto,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val photoUrl: String? = null,
    val isPremium: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// ---- pagination --------------------------------------------------------------------------------

@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

// ---- invoices ----------------------------------------------------------------------------------

@Serializable
data class SyncConflictDto(
    val id: String,
    val reason: String? = null,
)

// ---- templates ---------------------------------------------------------------------------------

@Serializable
data class TemplateDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String? = null,
    val thumbnailUrl: String? = null,
    val isPremium: Boolean = false,
    val currentVersion: Long = 1,
    val checksum: String = "",
    val isActive: Boolean = true,
)

// ---- media -------------------------------------------------------------------------------------

@Serializable
data class MediaDto(
    val url: String,
    val thumbnailUrl: String? = null,
    val contentType: String? = null,
)
