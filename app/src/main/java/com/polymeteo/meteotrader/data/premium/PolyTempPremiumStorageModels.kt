package com.polymeteo.meteotrader.data.premium

import kotlinx.serialization.Serializable

@Serializable
data class PolyTempPremiumDataset(
    val snapshots: List<PolyTempPremiumSnapshotRecord> = emptyList(),
    val verifications: List<PolyTempPremiumVerificationRecord> = emptyList(),
    val updatedAtEpochMs: Long = 0L
)

@Serializable
data class PolyTempPremiumSnapshotRecord(
    val cityId: String,
    val cityName: String,
    val cityZoneId: String,
    val targetDateIso: String,
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
