package com.polymeteo.meteotrader.data.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Dispatcher
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpResponse(
    val code: Int,
    val body: String,
    val url: String
)

class HttpClient(
    private val okHttpClient: OkHttpClient = defaultClient()
) {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        retries: Int = DEFAULT_GET_RETRIES
    ): HttpResponse = executeWithRetry(
        method = "GET",
        url = url,
        retries = retries
    ) {
        Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.get().build()
    }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        retries: Int = DEFAULT_POST_RETRIES
    ): HttpResponse = executeWithRetry(
        method = "POST",
        url = url,
        retries = retries
    ) {
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.post(requestBody).build()
    }

    private suspend fun executeWithRetry(
        method: String,
        url: String,
        retries: Int,
        requestFactory: () -> Request
    ): HttpResponse {
        val attempts = retries.coerceAtLeast(0) + 1
        var lastError: Throwable? = null

        repeat(attempts) { attemptIndex ->
            val responseOrError = runCatching {
                withContext(Dispatchers.IO) {
                    okHttpClient.newCall(requestFactory()).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        HttpResponse(
                            code = response.code,
                            body = body,
                            url = response.request.url.toString()
                        )
                    }
                }
            }

            if (responseOrError.isSuccess) {
                val response = responseOrError.getOrThrow()
                val shouldRetryStatus = attemptIndex < attempts - 1 && isRetryableStatus(response.code)
                if (!shouldRetryStatus) return response
                delay(retryDelayMs(attemptIndex))
                return@repeat
            }

            val throwable = responseOrError.exceptionOrNull()
            if (throwable != null) lastError = throwable
            val shouldRetryException = attemptIndex < attempts - 1 && isRetryableException(throwable)
            if (!shouldRetryException) {
                throw (throwable ?: IllegalStateException("$method $url failed"))
            }
            delay(retryDelayMs(attemptIndex))
        }

        throw (lastError ?: IllegalStateException("$method $url failed tras $attempts intentos"))
    }

    private fun isRetryableStatus(code: Int): Boolean {
        return code == 408 || code == 425 || code == 429 || code in 500..599
    }

    private fun isRetryableException(throwable: Throwable?): Boolean {
        return throwable is IOException
    }

    private fun retryDelayMs(attemptIndex: Int): Long {
        return when (attemptIndex) {
            0 -> 180L
            1 -> 420L
            else -> 900L
        }
    }

    companion object {
        private const val DEFAULT_GET_RETRIES = 1
        private const val DEFAULT_POST_RETRIES = 1

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 6
                }
            )
            .callTimeout(18, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
