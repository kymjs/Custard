package com.ai.assistance.custard.data.preferences

import android.content.Context
import com.ai.assistance.custard.data.model.CloudEmbeddingConfig
import com.ai.assistance.custard.data.model.MemoryScoreMode
import com.ai.assistance.custard.data.model.MemorySearchConfig

class MemorySearchSettingsPreferences(context: Context, profileId: String) {
    private val searchPrefs = context.applicationContext.getSharedPreferences(
        "memory_search_settings_$profileId",
        Context.MODE_PRIVATE
    )
    private val cloudPrefs = context.applicationContext.getSharedPreferences(
        "cloud_embedding_settings_$profileId",
        Context.MODE_PRIVATE
    )

    fun load(): MemorySearchConfig {
        val config = MemorySearchConfig(
            semanticThreshold = searchPrefs.getFloat(KEY_SEMANTIC_THRESHOLD, 0.6f),
            scoreMode = MemoryScoreMode.entries[searchPrefs.getInt(KEY_SCORE_MODE, MemoryScoreMode.BALANCED.ordinal)],
            keywordWeight = searchPrefs.getFloat(KEY_KEYWORD_WEIGHT, 10.0f),
            vectorWeight = searchPrefs.getFloat(KEY_VECTOR_WEIGHT, 0.0f),
            edgeWeight = searchPrefs.getFloat(KEY_EDGE_WEIGHT, 0.4f)
        )
        return config.normalized()
    }

    fun save(config: MemorySearchConfig) {
        val normalized = config.normalized()
        searchPrefs.edit()
            .putFloat(KEY_SEMANTIC_THRESHOLD, normalized.semanticThreshold)
            .putInt(KEY_SCORE_MODE, normalized.scoreMode.ordinal)
            .putFloat(KEY_KEYWORD_WEIGHT, normalized.keywordWeight)
            .putFloat(KEY_VECTOR_WEIGHT, normalized.vectorWeight)
            .putFloat(KEY_EDGE_WEIGHT, normalized.edgeWeight)
            .apply()
    }

    fun reset() {
        save(MemorySearchConfig())
    }

    fun loadCloudEmbedding(): CloudEmbeddingConfig {
        return CloudEmbeddingConfig(
            enabled = cloudPrefs.getBoolean(KEY_CLOUD_ENABLED, false),
            endpoint = cloudPrefs.getString(KEY_CLOUD_ENDPOINT, "") ?: "",
            apiKey = cloudPrefs.getString(KEY_CLOUD_API_KEY, "") ?: "",
            model = cloudPrefs.getString(KEY_CLOUD_MODEL, "") ?: ""
        ).normalized()
    }

    fun saveCloudEmbedding(config: CloudEmbeddingConfig) {
        val normalized = config.normalized()
        cloudPrefs.edit()
            .putBoolean(KEY_CLOUD_ENABLED, normalized.enabled)
            .putString(KEY_CLOUD_ENDPOINT, normalized.endpoint)
            .putString(KEY_CLOUD_API_KEY, normalized.apiKey)
            .putString(KEY_CLOUD_MODEL, normalized.model)
            .apply()
    }

    fun resetCloudEmbedding() {
        saveCloudEmbedding(CloudEmbeddingConfig())
    }

    companion object {
        private const val KEY_SEMANTIC_THRESHOLD = "semantic_threshold"
        private const val KEY_SCORE_MODE = "score_mode"
        private const val KEY_KEYWORD_WEIGHT = "keyword_weight"
        private const val KEY_VECTOR_WEIGHT = "vector_weight"
        private const val KEY_EDGE_WEIGHT = "edge_weight"

        private const val KEY_CLOUD_ENABLED = "enabled"
        private const val KEY_CLOUD_ENDPOINT = "endpoint"
        private const val KEY_CLOUD_API_KEY = "api_key"
        private const val KEY_CLOUD_MODEL = "model"
    }
}
