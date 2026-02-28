package com.kymjs.ai.custard.core.avatar.impl.factory

import androidx.compose.runtime.Composable
import com.kymjs.ai.custard.core.avatar.common.control.AvatarController
import com.kymjs.ai.custard.core.avatar.common.factory.AvatarControllerFactory
import com.kymjs.ai.custard.core.avatar.common.model.AvatarModel
import com.kymjs.ai.custard.core.avatar.common.model.AvatarType
import com.kymjs.ai.custard.core.avatar.common.model.ISkeletalAvatarModel
import com.kymjs.ai.custard.core.avatar.impl.dragonbones.control.rememberDragonBonesAvatarController
import com.kymjs.ai.custard.core.avatar.impl.gltf.control.rememberGltfAvatarController
import com.kymjs.ai.custard.core.avatar.impl.gltf.model.GltfAvatarModel
import com.kymjs.ai.custard.core.avatar.impl.mmd.control.rememberMmdAvatarController
import com.kymjs.ai.custard.core.avatar.impl.mmd.model.MmdAvatarModel
import com.kymjs.ai.custard.core.avatar.impl.webp.control.rememberWebPAvatarController
import com.kymjs.ai.custard.core.avatar.impl.webp.model.WebPAvatarModel

class AvatarControllerFactoryImpl : AvatarControllerFactory {

    @Composable
    override fun createController(model: AvatarModel): AvatarController? {
        return when (model.type) {
            AvatarType.DRAGONBONES -> {
                val skeletalModel = model as? ISkeletalAvatarModel
                if (skeletalModel != null) {
                    rememberDragonBonesAvatarController()
                } else {
                    null
                }
            }
            AvatarType.WEBP -> {
                val webpModel = model as? WebPAvatarModel
                if (webpModel != null) {
                    rememberWebPAvatarController(webpModel)
                } else {
                    null
                }
            }
            AvatarType.MMD -> {
                val mmdModel = model as? MmdAvatarModel
                if (mmdModel != null) {
                    rememberMmdAvatarController(mmdModel)
                } else {
                    null
                }
            }
            AvatarType.GLTF -> {
                val gltfModel = model as? GltfAvatarModel
                if (gltfModel != null) {
                    rememberGltfAvatarController(gltfModel)
                } else {
                    null
                }
            }
        }
    }

    override fun canCreateController(model: AvatarModel): Boolean {
        return when (model.type) {
            AvatarType.DRAGONBONES -> model is ISkeletalAvatarModel
            AvatarType.WEBP -> model is WebPAvatarModel
            AvatarType.MMD -> model is MmdAvatarModel
            AvatarType.GLTF -> model is GltfAvatarModel
        }
    }

    override val supportedTypes: List<String>
        get() = listOf(
            AvatarType.DRAGONBONES.name,
            AvatarType.WEBP.name,
            AvatarType.MMD.name,
            AvatarType.GLTF.name
        )
}
