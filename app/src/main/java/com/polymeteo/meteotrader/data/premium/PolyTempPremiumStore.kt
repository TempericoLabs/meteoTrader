package com.polymeteo.meteotrader.data.premium

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PolyTempPremiumStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val storageFile = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    suspend fun read(): PolyTempPremiumDataset = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!storageFile.exists()) {
                return@withLock PolyTempPremiumDataset()
            }

            val raw = runCatching { storageFile.readText() }
                .getOrDefault("")
                .trim()

            if (raw.isBlank()) {
                return@withLock PolyTempPremiumDataset()
            }

            runCatching {
                json.decodeFromString<PolyTempPremiumDataset>(raw)
            }.getOrElse {
                runCatching {
                    storageFile.renameTo(File(storageFile.parentFile, "$FILE_NAME.corrupt"))
                }
                PolyTempPremiumDataset()
            }
        }
    }

    suspend fun write(dataset: PolyTempPremiumDataset) = withContext(Dispatchers.IO) {
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
        private const val FILE_NAME = "polytemp_premium_dataset.json"
    }
}
