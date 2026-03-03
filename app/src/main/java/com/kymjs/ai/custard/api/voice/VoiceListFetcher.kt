package com.kymjs.ai.custard.api.voice

import com.kymjs.ai.custard.util.AppLogger
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object VoiceListFetcher {
    private const val TAG = "VoiceListFetcher"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    fun getVoicesListUrl(endpointUrl: String): List<String> {
        val base = extractBaseUrl(endpointUrl)
        return listOf(
            "$base/v1/audio/voices",
            "$base/v1/voices",
            "$base/voices"
        )
    }

    private fun extractBaseUrl(fullUrl: String): String {
        return try {
            val url = URL(fullUrl)
            val path = url.path
            val versionPathRegex = Regex("/v\\d+")
            val match = versionPathRegex.find(path)

            if (match != null) {
                val pathBeforeVersion = path.substring(0, match.range.first)
                "${url.protocol}://${url.authority}$pathBeforeVersion"
            } else {
                "${url.protocol}://${url.authority}"
            }
        } catch (e: Exception) {
            fullUrl
        }
    }

    suspend fun getVoicesList(
        apiKey: String,
        ttsEndpointUrl: String
    ): Result<List<VoiceService.Voice>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("apiKey is blank"))
        }
        if (ttsEndpointUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("ttsEndpointUrl is blank"))
        }

        val candidates = getVoicesListUrl(ttsEndpointUrl)
        var lastError: Throwable? = null

        for (url in candidates) {
            val request =
                Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

            try {
                val response = client.newCall(request).execute()
                try {
                    if (!response.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${response.code}")
                        continue
                    }

                    val body = response.body?.string().orEmpty()
                    val voices = parseVoices(body)
                    if (voices.isNotEmpty()) {
                        return@withContext Result.success(voices)
                    }

                    lastError = IllegalStateException("Empty voice list")
                } finally {
                    response.close()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Fetch voices failed: $url", e)
                lastError = e
            }
        }

        Result.failure(lastError ?: IllegalStateException("Fetch voices failed"))
    }

    private fun parseVoices(json: String): List<VoiceService.Voice> {
        return try {
            val root = JSONObject(json)
            val data = root.opt("data")
            val arr = when (data) {
                is JSONArray -> data
                else -> root.optJSONArray("voices") ?: root.optJSONArray("data")
            } ?: return emptyList()

            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { obj.optString("voice") }
                    if (id.isBlank()) continue
                    val name = obj.optString("name").ifBlank { id }
                    val locale = obj.optString("locale").ifBlank { "" }
                    val gender = obj.optString("gender").ifBlank { "" }
                    add(VoiceService.Voice(id = id, name = name, locale = locale, gender = gender))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
