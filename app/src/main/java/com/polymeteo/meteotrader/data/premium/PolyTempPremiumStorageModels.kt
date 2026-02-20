package com.polymeteo.meteotrader.data.premium

import kotlinx.serialization.Serializable

@Serializable
data class PolyTempPremiumDataset(
    val snapshots: List<PolyTempPremiumSnapshotRecord> = emptyList(),
    val verifications: List<PolyTempPremiumVerificationRecord> = emptyList(),
    val bootstrapCursors: List<PolyTempPremiumBootstrapCursor> = emptyList(),
    val updatedAtEpochMs: Long = 0L
)

@Serializable
data class PolyTempPremiumSnapshotRecord(
    val cityId: String,
    val cityName: String,
    val cityZoneId: String,
    val targetDateIso: String,
    val horizonDays: Int = 0,
    val providerId: String,
    val providerName: String,
    val capturedEpochMs: Long,
    val status: String,
    val forecastMaxC: Double? = null,
    val errorMessage: String? = null
)

@Serializable
data class PolyTempPremiumVerificationRecord(
    val cityId: String,
    val cityName: String,
    val cityZoneId: String,
    val targetDateIso: String,
    val horizonDays: Int = 0,
    val observedMaxC: Double,
    val observedSourceUrl: String? = null,
    val verifiedAtEpochMs: Long,
    val entries: List<PolyTempPremiumVerificationEntry> = emptyList()
)

@Serializable
data class PolyTempPremiumVerificationEntry(
    val providerId: String,
    val providerName: String,
    val capturedEpochMs: Long,
    val status: String,
    val forecastMaxC: Double? = null,
    val absoluteErrorC: Double? = null,
    val squaredErrorC: Double? = null,
    val errorMessage: String? = null
)

@Serializable
data class PolyTempPremiumBootstrapCursor(
    val cityId: String,
    val horizonDays: Int,
    val nextDateIso: String,
    val completed: Boolean = false,
    val lastAttemptEpochMs: Long = 0L,
    val lastSuccessEpochMs: Long = 0L,
    val lastError: String? = null
)
