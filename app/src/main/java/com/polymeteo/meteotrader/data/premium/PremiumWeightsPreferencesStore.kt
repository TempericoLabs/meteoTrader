package com.polymeteo.meteotrader.data.premium

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PremiumWeightsPreferencesStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    suspend fun read(
        cityId: String,
        horizonDays: Int
    ): PremiumWeightsCacheEntry? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val payload = loadPayload()
            payload.entries.firstOrNull { entry ->
                entry.cityId == cityId && entry.horizonDays == horizonDays
            }
        }
    }

    suspend fun write(entry: PremiumWeightsCacheEntry) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val payload = loadPayload()
            val updatedEntries = payload.entries
                .filterNot { item ->
                    item.cityId == entry.cityId && item.horizonDays == entry.horizonDays
                } + entry
            val updatedPayload = payload.copy(
                entries = updatedEntries.sortedWith(
                    compareBy<PremiumWeightsCacheEntry> { it.cityId }
                        .thenBy { it.horizonDays }
                ),
                updatedAtEpochMs = entry.generatedAtEpochMs
            )
            prefs.edit().putString(KEY_PAYLOAD, json.encodeToString(updatedPayload)).commit()
        }
    }

    private fun loadPayload(): PremiumWeightsCachePayload {
        val raw = prefs.getString(KEY_PAYLOAD, null) ?: return PremiumWeightsCachePayload()
        return runCatching {
            json.decodeFromString<PremiumWeightsCachePayload>(raw)
        }.getOrDefault(PremiumWeightsCachePayload())
    }

    private companion object {
        const val PREFS_NAME = "polymeteo_mma_weights"
        const val KEY_PAYLOAD = "mma_weights_payload"
    }
}

@Serializable
data class PremiumWeightsCachePayload(
    val entries: List<PremiumWeightsCacheEntry> = emptyList(),
    val updatedAtEpochMs: Long = 0L
)

@Serializable
data class PremiumWeightsCacheEntry(
    val cityId: String,
    val horizonDays: Int,
    val verificationSignature: String,
    val weightsByProviderId: Map<String, Double>,
    val verifiedDays: Int,
    val lastVerifiedDateIso: String? = null,
    val calibrationReady: Boolean,
    val calibrationProgress: Double,
    val warnings: List<String> = emptyList(),
    val generatedAtEpochMs: Long
)
