package com.polymeteo.meteotrader.data.paper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PaperTradingStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val storageFile = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    suspend fun read(): PaperTradingDataset = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!storageFile.exists()) {
                return@withLock PaperTradingDataset()
            }
            val raw = runCatching { storageFile.readText() }
                .getOrDefault("")
                .trim()
            if (raw.isBlank()) {
                return@withLock PaperTradingDataset()
            }

            runCatching {
                json.decodeFromString<PaperTradingDataset>(raw)
            }.getOrElse {
                runCatching {
                    storageFile.renameTo(File(storageFile.parentFile, "$FILE_NAME.corrupt"))
                }
                PaperTradingDataset()
            }
        }
    }

    suspend fun write(dataset: PaperTradingDataset) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tempFile = File(storageFile.parentFile, "$FILE_NAME.tmp")
            val content = json.encodeToString(dataset)
            tempFile.writeText(content)
            if (storageFile.exists()) {
                storageFile.delete()
            }
            tempFile.renameTo(storageFile)
        }
    }

    companion object {
        private const val FILE_NAME = "paper_trading_records.json"
    }
}
