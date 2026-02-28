package com.kymjs.ai.custard.ui.features.assistant.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.core.avatar.common.model.AvatarModel
import com.kymjs.ai.custard.core.avatar.common.model.AvatarType
import com.kymjs.ai.custard.core.avatar.common.state.AvatarEmotion
import com.kymjs.ai.custard.core.avatar.impl.factory.AvatarModelFactoryImpl
import com.kymjs.ai.custard.data.repository.AvatarConfig
import com.kymjs.ai.custard.data.repository.AvatarInstanceSettings
import com.kymjs.ai.custard.data.repository.AvatarRepository
import com.kymjs.ai.custard.data.repository.getEmotionAnimationMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AssistantConfigViewModel(
    private val repository: AvatarRepository,
    private val context: Context
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val avatarConfigs: List<AvatarConfig> = emptyList(),
        val currentAvatarConfig: AvatarConfig? = null,
        val currentAvatarModel: AvatarModel? = null,
        val config: AvatarInstanceSettings? = null,
        val emotionAnimationMapping: Map<AvatarEmotion, String> = emptyMap(),
        val errorMessage: String? = null,
        val operationSuccess: Boolean = false,
        val scrollPosition: Int = 0,
        val isImporting: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.configs,
                repository.currentAvatar,
                repository.instanceSettings
            ) { configs, currentAvatar, instanceSettings ->
                val persistedSettings = currentAvatar?.let { instanceSettings[it.id] }
                val currentSettings =
                    when {
                        currentAvatar == null -> AvatarInstanceSettings()
                        persistedSettings != null -> persistedSettings
                        currentAvatar.type == AvatarType.DRAGONBONES -> AvatarInstanceSettings(scale = 0.5f)
                        else -> AvatarInstanceSettings()
                    }
                val currentConfig =
                    currentAvatar?.let { avatar -> configs.find { it.id == avatar.id } }
                val emotionAnimationMapping = currentConfig?.getEmotionAnimationMapping().orEmpty()
                Triple(
                    configs,
                    currentConfig,
                    Triple(currentSettings, currentAvatar, emotionAnimationMapping)
                )
            }.collectLatest { (configs, currentConfig, mergedState) ->
                val (currentSettings, currentAvatar, emotionAnimationMapping) = mergedState
                updateUiState(
                    avatarConfigs = configs,
                    currentAvatarConfig = currentConfig,
                    currentAvatarModel = currentAvatar,
                    config = currentSettings,
                    emotionAnimationMapping = emotionAnimationMapping
                )
            }
        }
    }

    private fun updateUiState(
        isLoading: Boolean? = null,
        avatarConfigs: List<AvatarConfig>? = null,
        currentAvatarConfig: AvatarConfig? = null,
        currentAvatarModel: AvatarModel? = null,
        config: AvatarInstanceSettings? = null,
        emotionAnimationMapping: Map<AvatarEmotion, String>? = null,
        errorMessage: String? = null,
        operationSuccess: Boolean? = null,
        isImporting: Boolean? = null
    ) {
        val currentState = _uiState.value
        _uiState.value =
            currentState.copy(
                isLoading = isLoading ?: currentState.isLoading,
                avatarConfigs = avatarConfigs ?: currentState.avatarConfigs,
                currentAvatarConfig = currentAvatarConfig ?: currentState.currentAvatarConfig,
                currentAvatarModel = currentAvatarModel ?: currentState.currentAvatarModel,
                config = config ?: currentState.config,
                emotionAnimationMapping =
                    emotionAnimationMapping ?: currentState.emotionAnimationMapping,
                errorMessage = errorMessage,
                operationSuccess = operationSuccess ?: currentState.operationSuccess,
                scrollPosition = currentState.scrollPosition,
                isImporting = isImporting ?: currentState.isImporting
            )
    }

    fun switchAvatar(modelId: String) {
        viewModelScope.launch { repository.switchAvatar(modelId) }
    }

    fun updateScale(scale: Float) {
        val currentConfig = _uiState.value.config ?: return
        val avatarId = _uiState.value.currentAvatarConfig?.id ?: return
        val updatedConfig = currentConfig.copy(scale = scale)
        viewModelScope.launch { repository.updateAvatarSettings(avatarId, updatedConfig) }
    }

    fun updateTranslateX(translateX: Float) {
        val currentConfig = _uiState.value.config ?: return
        val avatarId = _uiState.value.currentAvatarConfig?.id ?: return
        val updatedConfig = currentConfig.copy(translateX = translateX)
        viewModelScope.launch { repository.updateAvatarSettings(avatarId, updatedConfig) }
    }

    fun updateTranslateY(translateY: Float) {
        val currentConfig = _uiState.value.config ?: return
        val avatarId = _uiState.value.currentAvatarConfig?.id ?: return
        val updatedConfig = currentConfig.copy(translateY = translateY)
        viewModelScope.launch { repository.updateAvatarSettings(avatarId, updatedConfig) }
    }

    fun updateCustomSetting(settingKey: String, value: Float?) {
        val currentConfig = _uiState.value.config ?: return
        val avatarId = _uiState.value.currentAvatarConfig?.id ?: return

        val updatedCustomSettings = currentConfig.customSettings.toMutableMap()
        if (value == null) {
            updatedCustomSettings.remove(settingKey)
        } else {
            updatedCustomSettings[settingKey] = value
        }

        val updatedConfig = currentConfig.copy(customSettings = updatedCustomSettings)
        viewModelScope.launch { repository.updateAvatarSettings(avatarId, updatedConfig) }
    }

    fun updateEmotionAnimationMapping(emotion: AvatarEmotion, animationName: String?) {
        val currentAvatarConfig = _uiState.value.currentAvatarConfig ?: return
        val mapping = currentAvatarConfig.getEmotionAnimationMapping().toMutableMap()

        val normalizedName = animationName?.trim().orEmpty()
        if (normalizedName.isBlank()) {
            mapping.remove(emotion)
        } else {
            mapping[emotion] = normalizedName
        }

        repository.updateAvatarEmotionAnimationMapping(currentAvatarConfig.id, mapping)
    }

    fun deleteAvatar(modelId: String) {
        updateUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val success = repository.deleteAvatar(modelId)
                updateUiState(
                    isLoading = false,
                    operationSuccess = success,
                    errorMessage =
                        if (!success) {
                            context.getString(R.string.error_occurred_simple)
                        } else {
                            null
                        }
                )
            } catch (e: Exception) {
                updateUiState(
                    isLoading = false,
                    operationSuccess = false,
                    errorMessage = context.getString(R.string.error_occurred, e.message)
                )
            }
        }
    }

    fun renameAvatar(modelId: String, newName: String) {
        updateUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val success = repository.renameAvatar(modelId, newName)
                updateUiState(
                    isLoading = false,
                    operationSuccess = success,
                    errorMessage =
                        if (!success) {
                            context.getString(R.string.error_occurred_simple)
                        } else {
                            null
                        }
                )
            } catch (e: Exception) {
                updateUiState(
                    isLoading = false,
                    operationSuccess = false,
                    errorMessage = context.getString(R.string.error_occurred, e.message)
                )
            }
        }
    }

    fun importAvatarFromZip(uri: Uri) {
        updateUiState(isLoading = true, isImporting = true)
        viewModelScope.launch {
            try {
                val success = repository.importAvatarFromUri(uri)
                updateUiState(
                    isLoading = false,
                    isImporting = false,
                    operationSuccess = success,
                    errorMessage =
                        if (!success) {
                            context.getString(R.string.error_occurred_simple)
                        } else {
                            null
                        }
                )
            } catch (e: Exception) {
                updateUiState(
                    isLoading = false,
                    isImporting = false,
                    operationSuccess = false,
                    errorMessage = context.getString(R.string.error_occurred, e.message)
                )
            }
        }
    }

    fun clearErrorMessage() {
        updateUiState(errorMessage = null)
    }

    fun clearOperationSuccess() {
        updateUiState(operationSuccess = false)
    }

    fun updateErrorMessage(message: String?) {
        updateUiState(errorMessage = message)
    }

    fun updateScrollPosition(position: Int) {
        _uiState.value = _uiState.value.copy(scrollPosition = position)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AssistantConfigViewModel::class.java)) {
                val modelFactory = AvatarModelFactoryImpl()
                val repository = AvatarRepository.getInstance(context, modelFactory)
                return AssistantConfigViewModel(repository, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
