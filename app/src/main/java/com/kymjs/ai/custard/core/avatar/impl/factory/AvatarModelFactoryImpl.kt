package com.kymjs.ai.custard.core.avatar.impl.factory

import com.kymjs.ai.custard.core.avatar.common.factory.AvatarModelFactory
import com.kymjs.ai.custard.core.avatar.common.model.AvatarModel
import com.kymjs.ai.custard.core.avatar.common.model.AvatarType
import com.kymjs.ai.custard.core.avatar.common.state.AvatarEmotion
import com.kymjs.ai.custard.core.avatar.impl.dragonbones.model.DragonBonesAvatarModel
import com.kymjs.ai.custard.core.avatar.impl.gltf.model.GltfAvatarModel
import com.kymjs.ai.custard.core.avatar.impl.mmd.model.MmdAvatarModel
import com.kymjs.ai.custard.core.avatar.impl.webp.model.WebPAvatarModel
import com.kymjs.ai.custard.data.model.DragonBonesModel

class AvatarModelFactoryImpl : AvatarModelFactory {

    override fun createModel(
        id: String,
        name: String,
        type: AvatarType,
        data: Map<String, Any>
    ): AvatarModel? {
        return when (type) {
            AvatarType.DRAGONBONES -> createDragonBonesModel(id, name, data)
            AvatarType.WEBP -> createWebPModel(id, name, data)
            AvatarType.MMD -> createMmdModel(id, name, data)
            AvatarType.GLTF -> createGltfModel(id, name, data)
        }
    }

    override fun createModelFromData(dataModel: Any): AvatarModel? {
        return when (dataModel) {
            is DragonBonesModel -> {
                DragonBonesAvatarModel(dataModel)
            }
            else -> {
                if (dataModel is Map<*, *>) {
                    val dataMap = dataModel as? Map<String, Any> ?: return null
                    val id = dataMap["id"] as? String ?: return null
                    val name = dataMap["name"] as? String ?: return null
                    val typeStr = dataMap["type"] as? String ?: return null
                    val type = try {
                        AvatarType.valueOf(typeStr)
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                    return createModel(id, name, type, dataMap)
                }
                null
            }
        }
    }

    override fun createDefaultModel(type: AvatarType, baseName: String): AvatarModel? {
        return when (type) {
            AvatarType.DRAGONBONES -> {
                val defaultData = mapOf(
                    "folderPath" to "assets/avatars/default",
                    "skeletonFile" to "default_ske.json",
                    "textureJsonFile" to "default_tex.json",
                    "textureImageFile" to "default_tex.png",
                    "isBuiltIn" to true
                )
                createDragonBonesModel("default_dragonbones", baseName, defaultData)
            }
            AvatarType.WEBP -> {
                WebPAvatarModel.createStandard(
                    id = "default_webp",
                    name = baseName,
                    basePath = "assets/avatars/default"
                )
            }
            AvatarType.MMD -> {
                val defaultData = mapOf(
                    "basePath" to "assets/avatars/default",
                    "modelFile" to "default.pmx"
                )
                createMmdModel("default_mmd", baseName, defaultData)
            }
            AvatarType.GLTF -> {
                val defaultData = mapOf(
                    "basePath" to "assets/avatars/default",
                    "modelFile" to "default.glb"
                )
                createGltfModel("default_gltf", baseName, defaultData)
            }
        }
    }

    override fun validateData(type: AvatarType, data: Map<String, Any>): Boolean {
        return when (type) {
            AvatarType.DRAGONBONES,
            AvatarType.WEBP,
            AvatarType.MMD,
            AvatarType.GLTF -> {
                val requiredKeys = getRequiredDataKeys(type)
                requiredKeys.all { key -> data.containsKey(key) && data[key] != null }
            }
        }
    }

    override val supportedTypes: List<AvatarType>
        get() = listOf(AvatarType.DRAGONBONES, AvatarType.WEBP, AvatarType.MMD, AvatarType.GLTF)

    override fun getRequiredDataKeys(type: AvatarType): List<String> {
        return when (type) {
            AvatarType.DRAGONBONES -> listOf(
                "folderPath",
                "skeletonFile",
                "textureJsonFile",
                "textureImageFile"
            )
            AvatarType.WEBP -> listOf("basePath")
            AvatarType.MMD -> listOf("basePath", "modelFile")
            AvatarType.GLTF -> listOf("basePath", "modelFile")
        }
    }

    private fun createDragonBonesModel(id: String, name: String, data: Map<String, Any>): AvatarModel? {
        return try {
            val folderPath = data["folderPath"] as? String ?: return null
            val skeletonFile = data["skeletonFile"] as? String ?: return null
            val textureJsonFile = data["textureJsonFile"] as? String ?: return null
            val textureImageFile = data["textureImageFile"] as? String ?: return null
            val isBuiltIn = data["isBuiltIn"] as? Boolean ?: false

            val dataModel = DragonBonesModel(
                id = id,
                name = name,
                folderPath = folderPath,
                skeletonFile = skeletonFile,
                textureJsonFile = textureJsonFile,
                textureImageFile = textureImageFile,
                isBuiltIn = isBuiltIn
            )

            DragonBonesAvatarModel(dataModel)
        } catch (e: Exception) {
            null
        }
    }

    private fun createWebPModel(id: String, name: String, data: Map<String, Any>): AvatarModel? {
        return try {
            val basePath = data["basePath"] as? String ?: return null
            val emotionMapData = data["emotionToFileMap"] as? Map<String, String>

            if (emotionMapData != null) {
                val emotionMap = emotionMapData.mapNotNull { (emotionStr, fileName) ->
                    try {
                        val emotion = AvatarEmotion.valueOf(emotionStr.uppercase())
                        emotion to fileName
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }.toMap()

                val currentEmotionStr = data["currentEmotion"] as? String
                val currentEmotion = if (currentEmotionStr != null) {
                    try {
                        AvatarEmotion.valueOf(currentEmotionStr.uppercase())
                    } catch (e: IllegalArgumentException) {
                        AvatarEmotion.IDLE
                    }
                } else {
                    AvatarEmotion.IDLE
                }

                WebPAvatarModel(
                    id = id,
                    name = name,
                    basePath = basePath,
                    emotionToFileMap = emotionMap,
                    currentEmotion = currentEmotion
                )
            } else {
                WebPAvatarModel.createStandard(
                    id = id,
                    name = name,
                    basePath = basePath
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createMmdModel(id: String, name: String, data: Map<String, Any>): AvatarModel? {
        return try {
            val basePath = data["basePath"] as? String ?: return null
            val modelFile = data["modelFile"] as? String ?: return null
            val motionFile = data["motionFile"] as? String
            val motionFiles = (data["motionFiles"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            MmdAvatarModel(
                id = id,
                name = name,
                basePath = basePath,
                modelFile = modelFile,
                motionFile = motionFile,
                motionFiles = if (motionFiles.isNotEmpty()) {
                    motionFiles
                } else {
                    motionFile?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun createGltfModel(id: String, name: String, data: Map<String, Any>): AvatarModel? {
        return try {
            val basePath = data["basePath"] as? String ?: return null
            val modelFile = data["modelFile"] as? String ?: return null
            val defaultAnimation = data["defaultAnimation"] as? String
            val animationNames = (data["animationNames"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            GltfAvatarModel(
                id = id,
                name = name,
                basePath = basePath,
                modelFile = modelFile,
                defaultAnimation = defaultAnimation,
                declaredAnimationNames = animationNames
            )
        } catch (e: Exception) {
            null
        }
    }
}
