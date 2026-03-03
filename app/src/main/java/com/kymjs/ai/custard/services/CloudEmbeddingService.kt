package com.kymjs.ai.custard.services

import com.kymjs.ai.custard.data.model.CloudEmbeddingConfig
import com.kymjs.ai.custard.data.model.Embedding
import com.kymjs.ai.custard.util.AppLogger
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class CloudEmbeddingService {

    companion object {
        private const val TAG = "CloudEmbeddingService"
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun generateEmbedding(config: CloudEmbeddingConfig, text: String): Embedding? = withContext(Dispatchers.IO) {
        val normalized = config.normalized()
        if (!normalized.isReady() || text.isBlank()) {
            return@withContext null
        }

        val requestBodyJson = JSONObject()
            .put("model", normalized.model)
            .put("input", text)
            .toString()

        val request = Request.Builder()
            .url(completeEmbeddingsEndpoint(normalized.endpoint))
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${normalized.apiKey}")
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "Embedding request failed: ${response.code} ${response.message}")
                    return@withContext null
                }
                parseEmbedding(responseBody)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Embedding request exception", e)
            null
        }
    }

    private fun parseEmbedding(responseBody: String): Embedding? {
        if (responseBody.isBlank()) return null

        return try {
            val root = JSONObject(responseBody)
            val data = root.optJSONArray("data") ?: return null
            if (data.length() <= 0) return null

            val first = data.optJSONObject(0) ?: return null
            val embeddingJson = first.optJSONArray("embedding") ?: return null
            if (embeddingJson.length() <= 0) return null

            val vector = FloatArray(embeddingJson.length()) { index ->
                embeddingJson.optDouble(index, 0.0).toFloat()
            }
            Embedding(vector)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse embedding response", e)
            null
        }
    }

    private fun completeEmbeddingsEndpoint(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.endsWith("#")) {
            return trimmed.removeSuffix("#")
        }

        val withoutSlash = trimmed.removeSuffix("/")

        return try {
            val path = URL(trimmed).path.removeSuffix("/")
            when {
                path.isEmpty() -> "$withoutSlash/v1/embeddings"
                path.endsWith("/v1", ignoreCase = true) -> "$withoutSlash/embeddings"
                else -> trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }
}
