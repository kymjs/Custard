package com.ai.assistance.mmd

import java.nio.FloatBuffer
import java.nio.IntBuffer

object MmdNative {

    init {
        MmdLibraryLoader.loadLibraries()
    }

    @JvmStatic external fun nativeIsAvailable(): Boolean

    @JvmStatic external fun nativeGetUnavailableReason(): String

    @JvmStatic external fun nativeGetLastError(): String

    @JvmStatic external fun nativeReadModelName(pathModel: String): String?

    @JvmStatic external fun nativeReadModelSummary(pathModel: String): LongArray?

    @JvmStatic external fun nativeReadMotionModelName(pathMotion: String): String?

    @JvmStatic external fun nativeReadMotionSummary(pathMotion: String): LongArray?

    @JvmStatic external fun nativeReadMotionMaxFrame(pathMotion: String): Int

    @JvmStatic external fun nativeBuildPreviewAnimatedMesh(pathModel: String, pathMotion: String, frame: Float): FloatArray?

    @JvmStatic external fun nativeBuildPreviewAnimatedMeshAuto(
        pathModel: String,
        pathMotion: String,
        isLooping: Boolean,
        restart: Boolean
    ): FloatArray?

    @JvmStatic external fun nativeRenderPreviewFrame(
        pathModel: String?,
        pathMotion: String?,
        isLooping: Boolean,
        restart: Boolean,
        rotationX: Float,
        rotationY: Float,
        rotationZ: Float,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        fitScale: Float,
        cameraDistance: Float,
        cameraTargetHeight: Float,
        aspectRatio: Float,
        nearClip: Float,
        farClip: Float,
        vertexBuffer: FloatBuffer?,
        vertexCount: Int,
        drawBatchData: IntBuffer?,
        textureIdsBySlot: IntBuffer?,
        program: Int,
        positionHandle: Int,
        normalHandle: Int,
        texCoordHandle: Int,
        mvpHandle: Int,
        modelHandle: Int,
        useTextureHandle: Int,
        textureSamplerHandle: Int
    ): Boolean

    @JvmStatic external fun nativeBuildPreviewMesh(pathModel: String): FloatArray?

    @JvmStatic external fun nativeBuildPreviewBatches(pathModel: String): IntArray?

    @JvmStatic external fun nativeReadPreviewTexturePath(pathModel: String): String?

    @JvmStatic external fun nativeReadPreviewTexturePaths(pathModel: String): Array<String>?

    @JvmStatic external fun nativeDecodeImageSize(pathImage: String): IntArray?

    @JvmStatic external fun nativeDecodeImageRgba(pathImage: String): ByteArray?
}
