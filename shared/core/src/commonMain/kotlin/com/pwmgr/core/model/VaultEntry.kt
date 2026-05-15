package com.pwmgr.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class EntryType { LOGIN, SECURE_NOTE, CARD, IDENTITY }

@Serializable
data class VaultEntry(
    val id: String,
    val type: EntryType,
    val title: String,
    val username: String? = null,
    val password: String? = null,
    val urls: List<String> = emptyList(),
    val notes: String? = null,
    val totpSeed: String? = null,
    val customFields: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

@Serializable
data class VaultPayload(
    val entries: List<VaultEntry> = emptyList(),
)
