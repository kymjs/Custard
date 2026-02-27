package com.ai.assistance.custard.data.preferences

import android.content.Context
import com.ai.assistance.custard.R
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.custard.data.model.PromptTag
import com.ai.assistance.custard.data.model.TagType

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.promptTagDataStore by preferencesDataStore(
    name = "prompt_tags"
)

/**
 * 提示词标签管理器
 */
class PromptTagManager private constructor(private val context: Context) {
    
    private val dataStore = context.promptTagDataStore
    
    companion object {
        private val PROMPT_TAG_LIST = stringSetPreferencesKey("prompt_tag_list")
        
        // 系统标签的固定ID
        const val SYSTEM_CHAT_TAG_ID = "system_chat_tag"
        const val SYSTEM_VOICE_TAG_ID = "system_voice_tag"
        const val SYSTEM_DESKTOP_PET_TAG_ID = "system_desktop_pet_tag"
        
        @Volatile
        private var INSTANCE: PromptTagManager? = null
        
        /**
         * 获取全局单例实例
         */
        fun getInstance(context: Context): PromptTagManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PromptTagManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 标签列表流
    val tagListFlow: Flow<List<String>> = dataStore.data.map { preferences ->
        preferences[PROMPT_TAG_LIST]?.toList() ?: emptyList()
    }

    val allTagsFlow: Flow<List<PromptTag>> = dataStore.data.map { preferences ->
        val tagIds = preferences[PROMPT_TAG_LIST]?.toList() ?: emptyList()
        tagIds.map { id ->
            getPromptTagFromPreferences(preferences, id)
        }.sortedByDescending { it.updatedAt }
    }
    
    // 获取标签流
    fun getPromptTagFlow(id: String): Flow<PromptTag> = dataStore.data.map { preferences ->
        getPromptTagFromPreferences(preferences, id)
    }
    
    // 从Preferences中获取标签
    private fun getPromptTagFromPreferences(preferences: Preferences, id: String): PromptTag {
        val nameKey = stringPreferencesKey("prompt_tag_${id}_name")
        val descriptionKey = stringPreferencesKey("prompt_tag_${id}_description")
        val promptContentKey = stringPreferencesKey("prompt_tag_${id}_prompt_content")
        val tagTypeKey = stringPreferencesKey("prompt_tag_${id}_tag_type")
        val isSystemTagKey = booleanPreferencesKey("prompt_tag_${id}_is_system_tag")
        val createdAtKey = longPreferencesKey("prompt_tag_${id}_created_at")
        val updatedAtKey = longPreferencesKey("prompt_tag_${id}_updated_at")
        
        return PromptTag(
            id = id,
            name = preferences[nameKey] ?: context.getString(R.string.prompt_tag_unnamed),
            description = preferences[descriptionKey] ?: "",
            promptContent = preferences[promptContentKey] ?: "",
            tagType = try {
                TagType.valueOf(preferences[tagTypeKey] ?: TagType.CUSTOM.name)
            } catch (e: IllegalArgumentException) {
                TagType.CUSTOM
            },
            isSystemTag = preferences[isSystemTagKey] ?: false,
            createdAt = preferences[createdAtKey] ?: System.currentTimeMillis(),
            updatedAt = preferences[updatedAtKey] ?: System.currentTimeMillis()
        )
    }
    
    // 创建标签
    suspend fun createPromptTag(
        name: String,
        description: String = "",
        promptContent: String = "",
        tagType: TagType = TagType.CUSTOM,
        isSystemTag: Boolean = false
    ): String {
        val id = UUID.randomUUID().toString()
        
        dataStore.edit { preferences ->
            // 添加到标签列表
            val currentList = preferences[PROMPT_TAG_LIST]?.toMutableSet() ?: mutableSetOf()
            currentList.add(id)
            preferences[PROMPT_TAG_LIST] = currentList
            
            // 设置标签数据
            val nameKey = stringPreferencesKey("prompt_tag_${id}_name")
            val descriptionKey = stringPreferencesKey("prompt_tag_${id}_description")
            val promptContentKey = stringPreferencesKey("prompt_tag_${id}_prompt_content")
            val tagTypeKey = stringPreferencesKey("prompt_tag_${id}_tag_type")
            val isSystemTagKey = booleanPreferencesKey("prompt_tag_${id}_is_system_tag")
            val createdAtKey = longPreferencesKey("prompt_tag_${id}_created_at")
            val updatedAtKey = longPreferencesKey("prompt_tag_${id}_updated_at")
            
            preferences[nameKey] = name
            preferences[descriptionKey] = description
            preferences[promptContentKey] = promptContent
            preferences[tagTypeKey] = tagType.name
            preferences[isSystemTagKey] = isSystemTag
            preferences[createdAtKey] = System.currentTimeMillis()
            preferences[updatedAtKey] = System.currentTimeMillis()
        }
        
        return id
    }
    
    // 更新标签
    suspend fun updatePromptTag(
        id: String,
        name: String? = null,
        description: String? = null,
        promptContent: String? = null,
        tagType: TagType? = null
    ) {
        val isSystem = isSystemTag(id)
        
        dataStore.edit { preferences ->
            name?.let { preferences[stringPreferencesKey("prompt_tag_${id}_name")] = it }
            description?.let { preferences[stringPreferencesKey("prompt_tag_${id}_description")] = it }
            promptContent?.let { preferences[stringPreferencesKey("prompt_tag_${id}_prompt_content")] = it }
            if (!isSystem) {
                tagType?.let { preferences[stringPreferencesKey("prompt_tag_${id}_tag_type")] = it.name }
            }
            
            // 更新修改时间
            preferences[longPreferencesKey("prompt_tag_${id}_updated_at")] = System.currentTimeMillis()
        }
    }
    
    // 删除标签
    suspend fun deletePromptTag(id: String) {
        // 不允许删除系统标签
        if (isSystemTag(id)) return
        
        dataStore.edit { preferences ->
            // 从列表中移除
            val currentList = preferences[PROMPT_TAG_LIST]?.toMutableSet() ?: mutableSetOf()
            currentList.remove(id)
            preferences[PROMPT_TAG_LIST] = currentList
            
            // 清除标签数据
            val keysToRemove = listOf(
                "prompt_tag_${id}_name",
                "prompt_tag_${id}_description",
                "prompt_tag_${id}_prompt_content",
                "prompt_tag_${id}_tag_type",
                "prompt_tag_${id}_is_system_tag",
                "prompt_tag_${id}_created_at",
                "prompt_tag_${id}_updated_at"
            )
            
            keysToRemove.forEach { key ->
                when {
                    key.endsWith("_is_system_tag") -> preferences.remove(booleanPreferencesKey(key))
                    key.endsWith("_created_at") || key.endsWith("_updated_at") -> preferences.remove(longPreferencesKey(key))
                    else -> preferences.remove(stringPreferencesKey(key))
                }
            }
        }
    }
    
    // 检查是否为系统标签
    private suspend fun isSystemTag(id: String): Boolean {
        return try {
            getPromptTagFlow(id).first().isSystemTag
        } catch (e: Exception) {
            false
        }
    }
    
    // 获取所有标签
    suspend fun getAllTags(): List<PromptTag> {
        val tagIds = tagListFlow.first()
        return tagIds.mapNotNull { id ->
            try {
                getPromptTagFlow(id).first()
            } catch (e: Exception) {
                null
            }
        }
    }
    
    // 获取系统标签
    suspend fun getSystemTags(): List<PromptTag> {
        return getAllTags().filter { it.isSystemTag }
    }
    
    // 获取自定义标签
    suspend fun getCustomTags(): List<PromptTag> {
        return getAllTags().filter { !it.isSystemTag }
    }
    
    // 根据类型获取标签
    suspend fun getTagsByType(tagType: TagType): List<PromptTag> {
        return getAllTags().filter { it.tagType == tagType }
    }
    
    // 查找具有相同内容的标签（不包括标签标题）
    suspend fun findTagWithSameContent(promptContent: String): PromptTag? {
        return getAllTags().find { tag ->
            tag.promptContent.trim() == promptContent.trim()
        }
    }
    
    // 创建或复用标签（如果内容相同则复用现有标签）
    suspend fun createOrReusePromptTag(
        name: String,
        description: String = "",
        promptContent: String = "",
        tagType: TagType = TagType.CUSTOM,
        isSystemTag: Boolean = false
    ): String {
        val existingTag = findTagWithSameContent(promptContent)
        return if (existingTag != null) {
            existingTag.id
        } else {
            createPromptTag(name, description, promptContent, tagType, isSystemTag)
        }
    }

    // 初始化系统标签
    suspend fun initializeSystemTags() {
        val promptVersionManager = PromptVersionManager<SystemTagSpec>()
        
        promptVersionManager.setVersions(mapOf(
            SYSTEM_CHAT_TAG_ID to SystemTagSpec(
                name = context.getString(R.string.prompt_tag_general_chat),
                description = context.getString(R.string.prompt_tag_general_chat_desc),
                key = "prompt_tag_$SYSTEM_CHAT_TAG_ID",
                defaultsByVersion = PromptVersionManager.defaults(
                    context.getString(R.string.prompt_tag_system_chat_prompt)
                )
            ),
            SYSTEM_VOICE_TAG_ID to SystemTagSpec(
                name = context.getString(R.string.prompt_tag_general_voice),
                description = context.getString(R.string.prompt_tag_general_voice_desc),
                key = "prompt_tag_$SYSTEM_VOICE_TAG_ID",
                defaultsByVersion = PromptVersionManager.defaults(
                    PromptBilingualData.getDefaultTone(context, "default_voice"),
                    PromptBilingualData.getVoiceToneV2(context)
                )
            ),
            SYSTEM_DESKTOP_PET_TAG_ID to SystemTagSpec(
                name = context.getString(R.string.prompt_tag_general_desktop_pet),
                description = context.getString(R.string.prompt_tag_general_desktop_pet_desc),
                key = "prompt_tag_$SYSTEM_DESKTOP_PET_TAG_ID",
                defaultsByVersion = PromptVersionManager.defaults(PromptBilingualData.getDefaultTone(context, "default_desktop_pet"))
            )
        ))

        dataStore.edit { preferences ->
            val tagListKey = PROMPT_TAG_LIST
            val currentList = preferences[tagListKey]?.toMutableSet() ?: mutableSetOf()
            var listModified = false

            if (promptVersionManager.isNeededUpdate(preferences)) {
                promptVersionManager.autoUpdate(preferences) { prefs, id, spec ->
                    updateSystemTagMetadata(prefs, id, spec)
                    
                    // 确保 ID 在列表中
                    if (currentList.add(id)) {
                        listModified = true
                    }
                }
            } else {
                // 如果不需要内容更新，但也需要检查是否存在（防止误删）
                // 这是一个额外的保护措施，确保所有定义的系统标签都在列表中
                // 但为了保持简洁和高性能，我们仅在 isNeededUpdate 为 true 时（通常是第一次运行或版本更新）做完全检查？
                // 或者是遍历所有 IDs 检查是否在 list 中？
                // 暂时仅在 update 时处理 list。
                
                // 补充：如果用户清除了数据但保留了文件？
                // 通常 isNeededUpdate 会处理这种情况（因为 Key 不存在时 shouldUpdate 返回 true）。
            }

            if (listModified) {
                preferences[tagListKey] = currentList
            }
        }
    }

    private data class SystemTagSpec(
        val name: String,
        val description: String,
        override val key: String,
        override val defaultsByVersion: Map<Int, String>
    ) : PromptVersionManager.VersionSpec

    /**
     * 更新系统标签的元数据（名称、描述、类型等），不包含 Prompt 内容
     */
    private fun updateSystemTagMetadata(
        preferences: MutablePreferences,
        id: String,
        spec: SystemTagSpec
    ) {
        val nameKey = stringPreferencesKey("prompt_tag_${id}_name")
        val descriptionKey = stringPreferencesKey("prompt_tag_${id}_description")
        val tagTypeKey = stringPreferencesKey("prompt_tag_${id}_tag_type")
        val isSystemTagKey = booleanPreferencesKey("prompt_tag_${id}_is_system_tag")
        val createdAtKey = longPreferencesKey("prompt_tag_${id}_created_at")
        val updatedAtKey = longPreferencesKey("prompt_tag_${id}_updated_at")

        val tagType = when (id) {
            SYSTEM_CHAT_TAG_ID -> TagType.SYSTEM_CHAT
            SYSTEM_VOICE_TAG_ID -> TagType.SYSTEM_VOICE
            SYSTEM_DESKTOP_PET_TAG_ID -> TagType.SYSTEM_DESKTOP_PET
            else -> TagType.CUSTOM
        }

        val now = System.currentTimeMillis()

        preferences[nameKey] = spec.name
        preferences[descriptionKey] = spec.description
        preferences[tagTypeKey] = tagType.name
        preferences[isSystemTagKey] = true
        if (preferences[createdAtKey] == null) {
            preferences[createdAtKey] = now
        }
        preferences[updatedAtKey] = now
    }
}