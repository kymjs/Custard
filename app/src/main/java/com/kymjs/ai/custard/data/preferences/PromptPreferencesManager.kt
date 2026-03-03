package com.kymjs.ai.custard.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kymjs.ai.custard.data.model.PromptProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.promptPreferencesDataStore by preferencesDataStore(
    name = "prompt_preferences"
)

/**
 * Manager for handling prompt profiles preferences
 */
class PromptPreferencesManager(private val context: Context) {

    private val dataStore = context.promptPreferencesDataStore

    // Keys
    companion object {
        private val PROMPT_PROFILE_LIST = stringSetPreferencesKey("prompt_profile_list")
        private val ACTIVE_PROFILE_ID = stringPreferencesKey("active_prompt_profile_id")
        
        // Helper function to create profile-specific keys
        private fun profileNameKey(id: String) = stringPreferencesKey("prompt_profile_${id}_name")
        private fun profileIntroPromptKey(id: String) = stringPreferencesKey("prompt_profile_${id}_intro_prompt")
        private fun profileTonePromptKey(id: String) = stringPreferencesKey("prompt_profile_${id}_tone_prompt")
        private fun profileIsDefaultKey(id: String) = booleanPreferencesKey("prompt_profile_${id}_is_default")
        
        // 固定ID，用于特定功能的默认提示词配置
        const val DEFAULT_CHAT_PROFILE_ID = "default_chat"
        const val DEFAULT_VOICE_PROFILE_ID = "default_voice"
        const val DEFAULT_DESKTOP_PET_PROFILE_ID = "default_desktop_pet"
    }

    // Default prompt values for standard usage
    private fun defaultIntroPrompt(profileId: String): String =
        PromptBilingualData.getDefaultIntro(context, profileId)

    private fun defaultTonePrompt(profileId: String): String =
        PromptBilingualData.getDefaultTone(context, profileId)

    private fun defaultProfileName(profileId: String): String =
        PromptBilingualData.getDefaultProfileName(context, profileId)
    
    // Flow of prompt profile list
    val profileListFlow: Flow<List<String>> = dataStore.data.map { preferences ->
        preferences[PROMPT_PROFILE_LIST]?.toList() ?: listOf("default")
    }

    // Flow of active profile ID
    val activeProfileIdFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[ACTIVE_PROFILE_ID] ?: "default"
    }

    // Get prompt profile by ID
    fun getPromptProfileFlow(profileId: String): Flow<PromptProfile> = dataStore.data.map { preferences ->
        val name = preferences[profileNameKey(profileId)] ?: defaultProfileName(profileId)
        val introPrompt = preferences[profileIntroPromptKey(profileId)] ?: defaultIntroPrompt(profileId)
        val tonePrompt = preferences[profileTonePromptKey(profileId)] ?: defaultTonePrompt(profileId)
        val isDefault = preferences[profileIsDefaultKey(profileId)] ?: (profileId == "default")
        val isActive = preferences[ACTIVE_PROFILE_ID] == profileId

        PromptProfile(
            id = profileId,
            name = name,
            introPrompt = introPrompt,
            tonePrompt = tonePrompt,
            isActive = isActive,
            isDefault = isDefault
        )
    }

    // Create a new prompt profile
    suspend fun createProfile(
        name: String,
        introPrompt: String? = null,
        tonePrompt: String? = null,
        isDefault: Boolean = false
    ): String {
        val id = if (isDefault) "default" else UUID.randomUUID().toString()

        dataStore.edit { preferences ->
            // Add to profile list if not default (default is always in the list)
            val currentList = preferences[PROMPT_PROFILE_LIST]?.toMutableSet() ?: mutableSetOf("default")
            if (!currentList.contains(id)) {
                currentList.add(id)
                preferences[PROMPT_PROFILE_LIST] = currentList
            }

            // Set profile data
            preferences[profileNameKey(id)] = name
            preferences[profileIntroPromptKey(id)] = introPrompt ?: defaultIntroPrompt(id)
            preferences[profileTonePromptKey(id)] = tonePrompt ?: defaultTonePrompt(id)
            preferences[profileIsDefaultKey(id)] = isDefault

            // If this is the first profile or is default, make it active
            if (isDefault || preferences[ACTIVE_PROFILE_ID] == null) {
                preferences[ACTIVE_PROFILE_ID] = id
            }
        }

        return id
    }

    // Delete a profile
    suspend fun deleteProfile(profileId: String) {
        // Don't allow deleting the default profile
        if (profileId == "default") return

        dataStore.edit { preferences ->
            // Remove from list
            val currentList = preferences[PROMPT_PROFILE_LIST]?.toMutableSet() ?: mutableSetOf("default")
            currentList.remove(profileId)
            preferences[PROMPT_PROFILE_LIST] = currentList

            // Clear profile data
            preferences.remove(profileNameKey(profileId))
            preferences.remove(profileIntroPromptKey(profileId))
            preferences.remove(profileTonePromptKey(profileId))
            preferences.remove(profileIsDefaultKey(profileId))

            // If this was the active profile, switch to default
            if (preferences[ACTIVE_PROFILE_ID] == profileId) {
                preferences[ACTIVE_PROFILE_ID] = "default"
            }
        }
    }

    // Set active profile
    suspend fun setActiveProfile(profileId: String) {
        dataStore.edit { preferences ->
            preferences[ACTIVE_PROFILE_ID] = profileId
        }
    }

    // Update prompt profile
    suspend fun updatePromptProfile(
        profileId: String,
        name: String? = null,
        introPrompt: String? = null,
        tonePrompt: String? = null
    ) {
        dataStore.edit { preferences ->
            name?.let { preferences[profileNameKey(profileId)] = it }
            introPrompt?.let { preferences[profileIntroPromptKey(profileId)] = it }
            tonePrompt?.let { preferences[profileTonePromptKey(profileId)] = it }
        }
    }

    // Initialize with default profiles if needed
    suspend fun initializeIfNeeded() {
        dataStore.edit { preferences ->
            val profileListKey = PROMPT_PROFILE_LIST
            val currentList = preferences[profileListKey]?.toMutableSet()

            if (currentList == null) {
                // --- Fresh Install ---
                val defaultProfiles = setOf(
                    "default",
                    DEFAULT_CHAT_PROFILE_ID,
                    DEFAULT_VOICE_PROFILE_ID,
                    DEFAULT_DESKTOP_PET_PROFILE_ID
                )
                preferences[profileListKey] = defaultProfiles
                preferences[ACTIVE_PROFILE_ID] = "default"

                // Set up all default profiles
                setupDefaultProfile(
                    preferences,
                    "default",
                    defaultProfileName("default"),
                    defaultIntroPrompt("default"),
                    defaultTonePrompt("default"),
                    true
                )
                setupDefaultProfile(
                    preferences,
                    DEFAULT_CHAT_PROFILE_ID,
                    defaultProfileName(DEFAULT_CHAT_PROFILE_ID),
                    defaultIntroPrompt(DEFAULT_CHAT_PROFILE_ID),
                    defaultTonePrompt(DEFAULT_CHAT_PROFILE_ID)
                )
                setupDefaultProfile(
                    preferences,
                    DEFAULT_VOICE_PROFILE_ID,
                    defaultProfileName(DEFAULT_VOICE_PROFILE_ID),
                    defaultIntroPrompt(DEFAULT_VOICE_PROFILE_ID),
                    defaultTonePrompt(DEFAULT_VOICE_PROFILE_ID)
                )
                setupDefaultProfile(
                    preferences,
                    DEFAULT_DESKTOP_PET_PROFILE_ID,
                    defaultProfileName(DEFAULT_DESKTOP_PET_PROFILE_ID),
                    defaultIntroPrompt(DEFAULT_DESKTOP_PET_PROFILE_ID),
                    defaultTonePrompt(DEFAULT_DESKTOP_PET_PROFILE_ID)
                )

            } else {
                // --- Migration for existing users ---
                var listModified = false
                val profilesToAdd = listOf(
                    DEFAULT_CHAT_PROFILE_ID,
                    DEFAULT_VOICE_PROFILE_ID,
                    DEFAULT_DESKTOP_PET_PROFILE_ID
                )

                profilesToAdd.forEach { id ->
                    if (!currentList.contains(id)) {
                        currentList.add(id)
                        setupDefaultProfile(
                            preferences,
                            id,
                            defaultProfileName(id),
                            defaultIntroPrompt(id),
                            defaultTonePrompt(id)
                        )
                        listModified = true
                    }
                }

                migrateDefaultProfilesIfUnmodified(preferences)

                if (listModified) {
                    preferences[profileListKey] = currentList
                }
            }
        }
    }

    private fun migrateDefaultProfilesIfUnmodified(preferences: MutablePreferences) {
        val defaultIds = listOf(
            "default",
            DEFAULT_CHAT_PROFILE_ID,
            DEFAULT_VOICE_PROFILE_ID,
            DEFAULT_DESKTOP_PET_PROFILE_ID
        )

        defaultIds.forEach { id ->
            val nameKey = profileNameKey(id)
            val introKey = profileIntroPromptKey(id)
            val toneKey = profileTonePromptKey(id)

            maybeUpdateBilingualValue(preferences, nameKey, PromptBilingualData.getDefaultProfileNameBilingual(id))
            maybeUpdateBilingualValue(preferences, introKey, PromptBilingualData.getDefaultIntroBilingual(id))
            maybeUpdateBilingualValue(preferences, toneKey, PromptBilingualData.getDefaultToneBilingual(id))

            if (id == "default") {
                val isDefaultKey = profileIsDefaultKey(id)
                if (preferences[isDefaultKey] == null) {
                    preferences[isDefaultKey] = true
                }
            }
        }
    }

    private fun maybeUpdateBilingualValue(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
        bilingual: PromptBilingualData.BilingualText
    ) {
        val desired = bilingual.forContext(context)
        val current = preferences[key]

        if (current == null) {
            preferences[key] = desired
            return
        }

        if (current == bilingual.zh || current == bilingual.en) {
            preferences[key] = desired
        }
    }
    
    // Helper function to set up a default profile's data
    private fun setupDefaultProfile(
        preferences: MutablePreferences,
        id: String,
        name: String,
        introPrompt: String,
        tonePrompt: String,
        isDefault: Boolean = false
    ) {
        preferences[profileNameKey(id)] = name
        preferences[profileIntroPromptKey(id)] = introPrompt
        preferences[profileTonePromptKey(id)] = tonePrompt
        preferences[profileIsDefaultKey(id)] = isDefault
    }
}