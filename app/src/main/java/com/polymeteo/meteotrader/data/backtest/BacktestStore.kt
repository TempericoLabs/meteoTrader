package com.polymeteo.meteotrader.data.backtest

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class BacktestStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val storageFile = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    suspend fun read(): BacktestDataset = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!storageFile.exists()) {
                return@withLock BacktestDataset()
            }

            val raw = runCatching { storageFile.readText() }
                .getOrDefault("")
                .trim()

            if (raw.isBlank()) {
                return@withLock BacktestDataset()
            }

            runCatching {
                json.decodeFromString<BacktestDataset>(raw)
            }.getOrElse {
                // Keep the damaged file for post-mortem.
                runCatching {
                    storageFile.renameTo(File(storageFile.parentFile, "$FILE_NAME.corrupt"))
                }
                BacktestDataset()
            }
        }
    }

    suspend fun write(dataset: BacktestDataset) = withContext(Dispatchers.IO) {
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
        private const val FILE_NAME = "backtest_records.json"
    }
}
