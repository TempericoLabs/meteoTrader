package com.polymeteo.meteotrader.data.model

import java.time.Instant
import java.time.LocalDate

data class PolyTempPremiumModelStat(
    val providerId: String,
    val providerName: String,
    val baseWeight: Double,
    val dynamicWeight: Double,
    val previousDynamicWeight: Double?,
    val multiplier: Double,
    val verifiedDays: Int,
    val validForecastDays: Int,
    val coverage: Double?,
    val meanAbsoluteErrorC: Double?,
    val rmseC: Double?,
    val withinOneDegreeRate: Double?,
    val score: Double?
)

data class PolyTempPremiumProviderDay(
    val providerId: String,
    val providerName: String,
    val capturedAt: Instant,
    val forecastMaxC: Double?,
    val absoluteErrorC: Double?,
    val status: SourceStatus,
    val errorMessage: String?
)

data class PolyTempPremiumVerificationDay(
    val targetDate: LocalDate,
    val observedMaxC: Double,
    val observedSourceUrl: String?,
    val verifiedAt: Instant,
    val providers: List<PolyTempPremiumProviderDay>
)

data class PolyTempPremiumReport(
    val cityId: String,
    val cityName: String,
    val generatedAt: Instant,
    val lastVerifiedDate: LocalDate?,
    val verifiedDays: Int,
    val pendingDays: Int,
    val providerRanking: List<PolyTempPremiumModelStat>,
    val dynamicWeights: Map<String, Double>,
    val dayVerifications: List<PolyTempPremiumVerificationDay>,
    val warnings: List<String>
) {
    companion object {
        fun empty(cityId: String, cityName: String, now: Instant = Instant.now()): PolyTempPremiumReport {
            return PolyTempPremiumReport(
                cityId = cityId,
                cityName = cityName,
                generatedAt = now,
                lastVerifiedDate = null,
                verifiedDays = 0,
                pendingDays = 0,
                providerRanking = emptyList(),
                dynamicWeights = emptyMap(),
                dayVerifications = emptyList(),
                warnings = emptyList()
            )
        }
    }
}
