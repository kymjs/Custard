package com.kymjs.ai.custard.core.avatar.impl.gltf.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kymjs.ai.custard.core.avatar.common.control.AvatarController
import com.kymjs.ai.custard.core.avatar.common.control.AvatarSettingKeys
import com.kymjs.ai.custard.core.avatar.common.state.AvatarEmotion
import com.kymjs.ai.custard.core.avatar.common.state.AvatarState
import com.kymjs.ai.custard.core.avatar.impl.gltf.model.GltfAvatarModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GltfAvatarController(
    private val model: GltfAvatarModel
) : AvatarController {

    private val _state = MutableStateFlow(AvatarState())
    override val state: StateFlow<AvatarState> = _state.asStateFlow()

    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()

    private val _translateX = MutableStateFlow(0.0f)
    val translateX: StateFlow<Float> = _translateX.asStateFlow()

    private val _translateY = MutableStateFlow(0.0f)
    val translateY: StateFlow<Float> = _translateY.asStateFlow()

    private val _cameraPitch = MutableStateFlow(8.0f)
    val cameraPitch: StateFlow<Float> = _cameraPitch.asStateFlow()

    private val _cameraYaw = MutableStateFlow(0.0f)
    val cameraYaw: StateFlow<Float> = _cameraYaw.asStateFlow()

    private val _cameraDistanceScale = MutableStateFlow(0.5f)
    val cameraDistanceScale: StateFlow<Float> = _cameraDistanceScale.asStateFlow()

    private val _cameraTargetHeight = MutableStateFlow(0.0f)
    val cameraTargetHeight: StateFlow<Float> = _cameraTargetHeight.asStateFlow()

    private val _availableAnimations = MutableStateFlow(model.normalizedDeclaredAnimationNames)
    override val availableAnimations: List<String>
        get() = _availableAnimations.value

    private var emotionAnimationMapping: Map<AvatarEmotion, String> = emptyMap()

    fun updateAvailableAnimations(discoveredAnimations: List<String>) {
        val normalized = discoveredAnimations
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val merged = if (normalized.isNotEmpty()) {
            normalized
        } else {
            model.normalizedDeclaredAnimationNames
        }

        _availableAnimations.value = merged

        if (merged.isEmpty()) {
            if (_state.value.currentAnimation != null) {
                _state.value = _state.value.copy(currentAnimation = null, isLooping = false)
            }
            return
        }

        val current = _state.value.currentAnimation
        if (current != null && merged.contains(current)) {
            return
        }

        val defaultAnimation = model.defaultAnimation?.trim()
        val next = if (!defaultAnimation.isNullOrBlank() && merged.contains(defaultAnimation)) {
            defaultAnimation
        } else {
            null
        }
        _state.value = _state.value.copy(currentAnimation = next, isLooping = next != null)
    }

    override fun setEmotion(newEmotion: AvatarEmotion) {
        _state.value = _state.value.copy(emotion = newEmotion)

        resolveAnimationForEmotion(newEmotion)?.let { animationName ->
            playAnimation(animationName, loop = 0)
        }
    }

    override fun playAnimation(animationName: String, loop: Int) {
        if (availableAnimations.isNotEmpty() && !availableAnimations.contains(animationName)) {
            return
        }

        _state.value = _state.value.copy(currentAnimation = null, isLooping = false)
        _state.value = _state.value.copy(
            currentAnimation = animationName,
            isLooping = loop == 0
        )
    }

    override fun lookAt(x: Float, y: Float) {
    }

    override fun updateSettings(settings: Map<String, Any>) {
        settings[AvatarSettingKeys.SCALE]?.let { if (it is Number) _scale.value = it.toFloat() }
        settings[AvatarSettingKeys.TRANSLATE_X]?.let { if (it is Number) _translateX.value = it.toFloat() }
        settings[AvatarSettingKeys.TRANSLATE_Y]?.let { if (it is Number) _translateY.value = it.toFloat() }

        settings[AvatarSettingKeys.GLTF_CAMERA_PITCH]?.let {
            if (it is Number) {
                _cameraPitch.value = it.toFloat().coerceIn(-89f, 89f)
            }
        }
        settings[AvatarSettingKeys.GLTF_CAMERA_YAW]?.let {
            if (it is Number) {
                _cameraYaw.value = it.toFloat().coerceIn(-180f, 180f)
            }
        }
        settings[AvatarSettingKeys.GLTF_CAMERA_DISTANCE_SCALE]?.let {
            if (it is Number) {
                _cameraDistanceScale.value = it.toFloat().coerceIn(0.0f, 10.0f)
            }
        }
        settings[AvatarSettingKeys.GLTF_CAMERA_TARGET_HEIGHT]?.let {
            if (it is Number) {
                _cameraTargetHeight.value = it.toFloat().coerceIn(-2.0f, 2.0f)
            }
        }
    }

    override fun updateEmotionAnimationMapping(mapping: Map<AvatarEmotion, String>) {
        emotionAnimationMapping = mapping
            .mapValues { (_, animationName) -> animationName.trim() }
            .filterValues { animationName -> animationName.isNotBlank() }
    }

    private fun resolveAnimationForEmotion(emotion: AvatarEmotion): String? {
        val preferred = emotionAnimationMapping[emotion]
        if (!preferred.isNullOrBlank()) {
            if (availableAnimations.isEmpty() || availableAnimations.contains(preferred)) {
                return preferred
            }
        }

        val idleFallback = emotionAnimationMapping[AvatarEmotion.IDLE]
        if (!idleFallback.isNullOrBlank()) {
            if (availableAnimations.isEmpty() || availableAnimations.contains(idleFallback)) {
                return idleFallback
            }
        }

        return availableAnimations.firstOrNull()
    }
}

@Composable
fun rememberGltfAvatarController(model: GltfAvatarModel): GltfAvatarController {
    return remember(model) { GltfAvatarController(model) }
}
