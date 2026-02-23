package com.polymeteo.meteotrader.data.paper

import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PaperExecutionMode
import com.polymeteo.meteotrader.data.model.PaperPortfolioSnapshot
import com.polymeteo.meteotrader.data.model.PaperPosition
import com.polymeteo.meteotrader.data.model.PaperPositionStatus
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class PaperTradingEngine(
    private val store: PaperTradingStore,
    private val nowProvider: () -> Instant = { Instant.now() }
) {

    suspend fun loadPortfolio(cities: List<CityWeatherData>): PaperPortfolioSnapshot {
        val now = nowProvider()
        val dataset = store.read()
        val marketMap = buildMarketMap(cities)
        val markedRecords = dataset.positions.map { record ->
            if (record.status == PaperPositionStatus.CLOSED) {
                record
            } else {
                val quote = marketMap[record.marketId]
                val markPrice = quote?.yesPrice ?: record.lastMarkYesPrice
                if (markPrice == record.lastMarkYesPrice) {
                    record
                } else {
                    record.copy(lastMarkYesPrice = markPrice)
                }
            }
        }

        if (markedRecords != dataset.positions) {
            store.write(
                dataset.copy(
                    positions = markedRecords,
                    updatedAtEpochMs = now.toEpochMilli()
                )
            )
        }

        return buildSnapshot(
            records = markedRecords,
            marketMap = marketMap,
            generatedAt = now
        )
    }

    suspend fun placeBuyYes(
        cityData: CityWeatherData,
        opportunity: TraderOpportunity,
        stakeUsdc: Double,
        currentCities: List<CityWeatherData>
    ): PaperPortfolioSnapshot {
        require(stakeUsdc in MIN_STAKE_USDC..MAX_STAKE_USDC) {
            "Stake fuera de rango ($MIN_STAKE_USDC - $MAX_STAKE_USDC USDC)"
        }

        val rawYesPrice = opportunity.yesPrice.coerceIn(MIN_PRICE, MAX_PRICE)
        val spread = (opportunity.spread ?: DEFAULT_SPREAD).coerceIn(0.0, MAX_SPREAD)
        val entryYesPrice = (rawYesPrice + (spread * BUY_SPREAD_IMPACT_FACTOR))
            .coerceIn(MIN_PRICE, MAX_PRICE)
        val sharesYes = stakeUsdc / entryYesPrice
        val entryFeeUsdc = stakeUsdc * ENTRY_FEE_RATE
        val totalCostUsdc = stakeUsdc + entryFeeUsdc
        val now = nowProvider()

        val record = PaperPositionRecord(
            id = buildPositionId(opportunity.marketId, now),
            cityId = cityData.city.id,
            cityName = cityData.city.name,
            marketId = opportunity.marketId,
            question = opportunity.question,
            targetDateIso = opportunity.condition.targetDate?.toString(),
            status = PaperPositionStatus.OPEN,
            stakeUsdc = stakeUsdc,
            sharesYes = sharesYes,
            entryYesPrice = entryYesPrice,
            entryFeeUsdc = entryFeeUsdc,
            totalCostUsdc = totalCostUsdc,
            lastMarkYesPrice = rawYesPrice,
            openedAtEpochMs = now.toEpochMilli()
        )

        val current = store.read()
        store.write(
            current.copy(
                positions = current.positions + record,
                updatedAtEpochMs = now.toEpochMilli()
            )
        )

        return loadPortfolio(currentCities)
    }

    suspend fun closePosition(
        positionId: String,
        currentCities: List<CityWeatherData>
    ): PaperPortfolioSnapshot {
        val now = nowProvider()
        val current = store.read()
        val marketMap = buildMarketMap(currentCities)
        val updatedPositions = current.positions.map { record ->
            if (record.id != positionId || record.status != PaperPositionStatus.OPEN) {
                return@map record
            }

            val quote = marketMap[record.marketId]
            val rawYesPrice = quote?.yesPrice
                ?: record.lastMarkYesPrice
                ?: record.entryYesPrice
            val spread = quote?.spread?.coerceIn(0.0, MAX_SPREAD) ?: DEFAULT_SPREAD
            val exitYesPrice = max(
                MIN_PRICE,
                rawYesPrice - (spread * SELL_SPREAD_IMPACT_FACTOR)
            ).coerceIn(MIN_PRICE, MAX_PRICE)

            val proceedsGross = record.sharesYes * exitYesPrice
            val closeFeeUsdc = proceedsGross * EXIT_FEE_RATE
            val proceedsNetUsdc = proceedsGross - closeFeeUsdc
            val realizedPnlUsdc = proceedsNetUsdc - record.totalCostUsdc

            record.copy(
                status = PaperPositionStatus.CLOSED,
                lastMarkYesPrice = rawYesPrice,
                closeYesPrice = exitYesPrice,
                closeFeeUsdc = closeFeeUsdc,
                proceedsNetUsdc = proceedsNetUsdc,
                realizedPnlUsdc = realizedPnlUsdc,
                closedAtEpochMs = now.toEpochMilli()
            )
        }

        store.write(
            current.copy(
                positions = updatedPositions,
                updatedAtEpochMs = now.toEpochMilli()
            )
        )

        return loadPortfolio(currentCities)
    }

    private fun buildSnapshot(
        records: List<PaperPositionRecord>,
        marketMap: Map<String, MarketQuote>,
        generatedAt: Instant
    ): PaperPortfolioSnapshot {
        val warnings = mutableListOf<String>()
        val positions = records.map { record ->
            val quote = marketMap[record.marketId]
            val currentYesPrice = when (record.status) {
                PaperPositionStatus.OPEN -> quote?.yesPrice ?: record.lastMarkYesPrice
                PaperPositionStatus.CLOSED -> record.closeYesPrice ?: record.lastMarkYesPrice
            }

            if (record.status == PaperPositionStatus.OPEN && quote == null) {
                warnings += "Sin quote en vivo para ${record.marketId} (se usa última marca)."
            }

            val currentValue = when (record.status) {
                PaperPositionStatus.OPEN -> currentYesPrice?.let { record.sharesYes * it }
                PaperPositionStatus.CLOSED -> record.proceedsNetUsdc
            }
            val unrealizedPnl = if (record.status == PaperPositionStatus.OPEN && currentValue != null) {
                currentValue - record.totalCostUsdc
            } else {
                null
            }

            PaperPosition(
                id = record.id,
                cityId = record.cityId,
                cityName = record.cityName,
                marketId = record.marketId,
                question = record.question,
                targetDate = parseDate(record.targetDateIso),
                status = record.status,
                stakeUsdc = record.stakeUsdc,
                sharesYes = record.sharesYes,
                entryYesPrice = record.entryYesPrice,
                entryFeeUsdc = record.entryFeeUsdc,
                totalCostUsdc = record.totalCostUsdc,
                currentYesPrice = currentYesPrice,
                currentValueUsdc = currentValue,
                unrealizedPnlUsdc = unrealizedPnl,
                closeYesPrice = record.closeYesPrice,
                closeFeeUsdc = record.closeFeeUsdc,
                proceedsNetUsdc = record.proceedsNetUsdc,
                realizedPnlUsdc = record.realizedPnlUsdc,
                openedAt = Instant.ofEpochMilli(record.openedAtEpochMs),
                closedAt = record.closedAtEpochMs?.let(Instant::ofEpochMilli)
            )
        }.sortedByDescending { it.openedAt }

        val openPositions = positions.filter { it.status == PaperPositionStatus.OPEN }
        val closedPositions = positions.filter { it.status == PaperPositionStatus.CLOSED }

        val openCostUsdc = openPositions.sumOf { it.totalCostUsdc }
        val openMarketValueUsdc = openPositions.sumOf { it.currentValueUsdc ?: 0.0 }
        val openUnrealizedPnlUsdc = openPositions.sumOf { it.unrealizedPnlUsdc ?: 0.0 }
        val closedRealizedPnlUsdc = closedPositions.sumOf { it.realizedPnlUsdc ?: 0.0 }

        return PaperPortfolioSnapshot(
            mode = PaperExecutionMode.SIMULATION,
            generatedAt = generatedAt,
            positions = positions,
            openPositions = openPositions.size,
            closedPositions = closedPositions.size,
            totalStakeUsdc = positions.sumOf { it.stakeUsdc },
            openCostUsdc = openCostUsdc,
            openMarketValueUsdc = openMarketValueUsdc,
            openUnrealizedPnlUsdc = openUnrealizedPnlUsdc,
            closedRealizedPnlUsdc = closedRealizedPnlUsdc,
            warnings = warnings.distinct()
        )
    }

    private fun buildMarketMap(cities: List<CityWeatherData>): Map<String, MarketQuote> {
        return cities
            .asSequence()
            .flatMap { cityData ->
                cityData.polymarket.opportunities.asSequence().map { opportunity ->
                    opportunity.marketId to MarketQuote(
                        cityId = cityData.city.id,
                        yesPrice = opportunity.yesPrice.coerceIn(MIN_PRICE, MAX_PRICE),
                        spread = (opportunity.spread ?: DEFAULT_SPREAD).coerceIn(0.0, MAX_SPREAD)
                    )
                }
            }
            .toMap()
    }

    private fun parseDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }

    private fun buildPositionId(marketId: String, instant: Instant): String {
        return "$marketId-${instant.toEpochMilli()}-${UUID.randomUUID().toString().take(8)}"
            .lowercase(Locale.US)
    }

    private data class MarketQuote(
        val cityId: String,
        val yesPrice: Double,
        val spread: Double
    )

    companion object {
        private const val MIN_STAKE_USDC = 1.0
        private const val MAX_STAKE_USDC = 25_000.0
        private const val MIN_PRICE = 0.001
        private const val MAX_PRICE = 0.999
        private const val DEFAULT_SPREAD = 0.02
        private const val MAX_SPREAD = 0.30
        private const val ENTRY_FEE_RATE = 0.009
        private const val EXIT_FEE_RATE = 0.009
        private const val BUY_SPREAD_IMPACT_FACTOR = 0.50
        private const val SELL_SPREAD_IMPACT_FACTOR = 0.50
    }
}
