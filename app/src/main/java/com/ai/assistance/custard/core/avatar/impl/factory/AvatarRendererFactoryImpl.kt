package com.ai.assistance.custard.core.avatar.impl.factory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ai.assistance.custard.core.avatar.common.control.AvatarController
import com.ai.assistance.custard.core.avatar.common.factory.AvatarRendererFactory
import com.ai.assistance.custard.core.avatar.common.model.AvatarModel
import com.ai.assistance.custard.core.avatar.common.model.AvatarType
import com.ai.assistance.custard.core.avatar.common.model.IFrameSequenceAvatarModel
import com.ai.assistance.custard.core.avatar.common.model.ISkeletalAvatarModel
import com.ai.assistance.custard.core.avatar.impl.dragonbones.view.DragonBonesRenderer
import com.ai.assistance.custard.core.avatar.impl.gltf.model.GltfAvatarModel
import com.ai.assistance.custard.core.avatar.impl.gltf.view.GltfRenderer
import com.ai.assistance.custard.core.avatar.impl.mmd.model.MmdAvatarModel
import com.ai.assistance.custard.core.avatar.impl.mmd.view.MmdRenderer
import com.ai.assistance.custard.core.avatar.impl.webp.view.WebPRenderer

class AvatarRendererFactoryImpl : AvatarRendererFactory {

    @Composable
    override fun createRenderer(model: AvatarModel): @Composable ((modifier: Modifier, controller: AvatarController) -> Unit)? {
        return when (model.type) {
            AvatarType.DRAGONBONES -> {
                val skeletalModel = model as? ISkeletalAvatarModel
                if (skeletalModel != null) {
                    { modifier, controller ->
                        DragonBonesRenderer(
                            modifier = modifier,
                            model = skeletalModel,
                            controller = controller,
                            onError = { }
                        )
                    }
                } else {
                    null
                }
            }
            AvatarType.WEBP -> {
                val frameSequenceModel = model as? IFrameSequenceAvatarModel
                if (frameSequenceModel != null) {
                    { modifier, controller ->
                        WebPRenderer(
                            modifier = modifier,
                            model = frameSequenceModel,
                            controller = controller,
                            onError = { }
                        )
                    }
                } else {
                    null
                }
            }
            AvatarType.MMD -> {
                val mmdModel = model as? MmdAvatarModel
                if (mmdModel != null) {
                    { modifier, controller ->
                        MmdRenderer(
                            modifier = modifier,
                            model = mmdModel,
                            controller = controller,
                            onError = { }
                        )
                    }
                } else {
                    null
                }
            }
            AvatarType.GLTF -> {
                val gltfModel = model as? GltfAvatarModel
                if (gltfModel != null) {
                    { modifier, controller ->
                        GltfRenderer(
                            modifier = modifier,
                            model = gltfModel,
                            controller = controller,
                            onError = { }
                        )
                    }
                } else {
                    null
                }
            }
        }
    }
}
