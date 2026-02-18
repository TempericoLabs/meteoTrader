package com.polymeteo.meteotrader.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

data class HttpResponse(
    val code: Int,
    val body: String,
    val url: String
)

class HttpClient(
    private val okHttpClient: OkHttpClient = defaultClient()
) {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) -> requestBuilder.header(key, value) }
            okHttpClient.newCall(requestBuilder.get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                HttpResponse(
                    code = response.code,
                    body = body,
                    url = response.request.url.toString()
                )
            }
        }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse = withContext(Dispatchers.IO) {
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder().url(url).post(requestBody)
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            HttpResponse(
                code = response.code,
                body = body,
                url = response.request.url.toString()
            )
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 6
                }
            )
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
