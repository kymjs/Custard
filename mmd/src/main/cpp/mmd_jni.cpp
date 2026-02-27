#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <GLES2/gl2.h>

#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
#include "Saba/Model/MMD/PMDFile.h"
#include "Saba/Model/MMD/PMDModel.h"
#include "Saba/Model/MMD/PMXFile.h"
#include "Saba/Model/MMD/PMXModel.h"
#include "Saba/Model/MMD/VMDAnimation.h"
#include "Saba/Model/MMD/VMDFile.h"
#include "glm/glm.hpp"
#include "glm/gtc/matrix_transform.hpp"
#include "glm/gtc/type_ptr.hpp"

#ifndef STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_IMPLEMENTATION
#endif
#include "stb_image.h"
#endif

#define TAG "MmdNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int64_t kModelFormatPmd = 1;
constexpr int64_t kModelFormatPmx = 2;

constexpr const char* kUnavailableReason =
    "saba submodule not found. Ensure mmd/third_party/saba exists and is initialized.";

std::mutex gLastErrorMutex;
std::string gLastError;

void setLastError(const std::string& error) {
    {
        std::lock_guard<std::mutex> lock(gLastErrorMutex);
        gLastError = error;
    }
    LOGE("%s", error.c_str());
}

void clearLastError() {
    std::lock_guard<std::mutex> lock(gLastErrorMutex);
    gLastError.clear();
}

std::string getLastErrorCopy() {
    std::lock_guard<std::mutex> lock(gLastErrorMutex);
    return gLastError;
}

std::string jstringToString(JNIEnv* env, jstring jvalue) {
    if (jvalue == nullptr) {
        return "";
    }

    const char* cvalue = env->GetStringUTFChars(jvalue, nullptr);
    if (cvalue == nullptr) {
        return "";
    }

    std::string value(cvalue);
    env->ReleaseStringUTFChars(jvalue, cvalue);
    return value;
}

jstring stringToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string toLowerAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    return value;
}

std::string getFileExtension(const std::string& filepath) {
    const size_t dotPosition = filepath.find_last_of('.');
    if (dotPosition == std::string::npos) {
        return "";
    }

    return toLowerAscii(filepath.substr(dotPosition + 1));
}

std::string trimAscii(std::string value) {
    value.erase(value.begin(), std::find_if(value.begin(), value.end(), [](unsigned char ch) {
        return !std::isspace(ch);
    }));

    value.erase(std::find_if(value.rbegin(), value.rend(), [](unsigned char ch) {
        return !std::isspace(ch);
    }).base(), value.end());

    return value;
}

std::string normalizePathSeparators(std::string path) {
    std::replace(path.begin(), path.end(), '\\', '/');
    return path;
}

bool isAbsolutePath(const std::string& path) {
    if (path.empty()) {
        return false;
    }

    if (path[0] == '/' || path[0] == '\\') {
        return true;
    }

    return path.size() > 1 && path[1] == ':';
}

std::string getParentDirectory(const std::string& path) {
    const size_t slashPosition = path.find_last_of("/\\");
    if (slashPosition == std::string::npos) {
        return "";
    }

    return path.substr(0, slashPosition);
}

std::string joinPaths(const std::string& base, const std::string& relative) {
    if (base.empty()) {
        return relative;
    }
    if (relative.empty()) {
        return base;
    }

    if (base.back() == '/' || base.back() == '\\') {
        return base + relative;
    }

    return base + "/" + relative;
}

bool isSupportedDiffuseTextureExtension(const std::string& path) {
    const std::string ext = getFileExtension(path);
    return ext == "png" ||
        ext == "jpg" ||
        ext == "jpeg" ||
        ext == "bmp" ||
        ext == "tga" ||
        ext == "gif" ||
        ext == "webp" ||
        ext == "dds";
}

#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA

enum class ModelFileType {
    Unknown = 0,
    Pmd,
    Pmx,
};

struct ModelParseResult {
    ModelFileType fileType = ModelFileType::Unknown;
    std::string modelName;
    int64_t vertexCount = 0;
    int64_t faceCount = 0;
    int64_t materialCount = 0;
    int64_t boneCount = 0;
    int64_t morphCount = 0;
    int64_t rigidBodyCount = 0;
    int64_t jointCount = 0;
};

struct MotionParseResult {
    std::string modelName;
    int64_t motionCount = 0;
    int64_t morphCount = 0;
    int64_t cameraCount = 0;
    int64_t lightCount = 0;
    int64_t shadowCount = 0;
    int64_t ikCount = 0;
};

struct PreviewRenderData {
    std::vector<float> vertices;
    std::vector<int32_t> batches;
    std::vector<std::string> texturePaths;
    std::vector<uint32_t> vertexIndices;
};

struct PreviewRenderCache {
    std::string modelPath;
    PreviewRenderData data;
};

struct AnimatedRuntimeCache {
    std::string modelPath;
    std::string motionPath;
    std::shared_ptr<saba::MMDModel> model;
    std::unique_ptr<saba::VMDAnimation> animation;
    std::vector<uint32_t> previewVertexIndices;
    std::vector<float> animatedVertices;
    int32_t maxMotionFrame = 0;
};

struct ImageDecodeCache {
    std::string imagePath;
    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgbaPixels;
};

constexpr size_t kPreviewVertexStride = 8;
constexpr size_t kMaxPreviewTriangles = 500000;

std::mutex gPreviewCacheMutex;
PreviewRenderCache gPreviewCache;

std::mutex gAnimatedRuntimeCacheMutex;
AnimatedRuntimeCache gAnimatedRuntimeCache;

std::mutex gImageDecodeCacheMutex;
ImageDecodeCache gImageDecodeCache;

std::mutex gAutoAnimationClockMutex;
std::string gAutoAnimationModelPath;
std::string gAutoAnimationMotionPath;
bool gAutoAnimationLooping = false;
bool gAutoAnimationClockStarted = false;
std::chrono::steady_clock::time_point gAutoAnimationStartedAt;
std::unordered_map<std::string, int32_t> gMotionMaxFrameCache;

inline void appendPreviewVertex(
    std::vector<float>* outVertices,
    const glm::vec3& position,
    const glm::vec3& normal,
    const glm::vec2& uv
) {
    outVertices->push_back(position.x);
    outVertices->push_back(position.y);
    outVertices->push_back(position.z);
    outVertices->push_back(normal.x);
    outVertices->push_back(normal.y);
    outVertices->push_back(normal.z);
    outVertices->push_back(uv.x);
    outVertices->push_back(uv.y);
}

ModelFileType detectModelFileType(const std::string& modelPath) {
    const std::string extension = getFileExtension(modelPath);
    if (extension == "pmd") {
        return ModelFileType::Pmd;
    }
    if (extension == "pmx") {
        return ModelFileType::Pmx;
    }
    return ModelFileType::Unknown;
}

int64_t toModelFormatId(ModelFileType fileType) {
    if (fileType == ModelFileType::Pmd) {
        return kModelFormatPmd;
    }
    if (fileType == ModelFileType::Pmx) {
        return kModelFormatPmx;
    }
    return 0;
}

std::string normalizeTexturePath(const std::string& baseDir, const std::string& textureNameRaw) {
    std::string textureName = normalizePathSeparators(trimAscii(textureNameRaw));
    if (textureName.empty()) {
        return "";
    }

    if (isAbsolutePath(textureName)) {
        return normalizePathSeparators(textureName);
    }

    return normalizePathSeparators(joinPaths(baseDir, textureName));
}

bool appendBatch(
    std::vector<int32_t>* outBatches,
    size_t startVertex,
    size_t vertexCount,
    int textureSlot,
    std::string* outError
) {
    if (vertexCount == 0) {
        return true;
    }

    if (startVertex > static_cast<size_t>(std::numeric_limits<int32_t>::max()) ||
        vertexCount > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        if (outError != nullptr) {
            *outError = "preview batch index overflow.";
        }
        return false;
    }

    const int32_t safeTextureSlot = textureSlot >= 0 ? static_cast<int32_t>(textureSlot) : static_cast<int32_t>(-1);
    outBatches->push_back(static_cast<int32_t>(startVertex));
    outBatches->push_back(static_cast<int32_t>(vertexCount));
    outBatches->push_back(safeTextureSlot);
    return true;
}

int getOrCreateTextureSlot(
    const std::string& texturePath,
    PreviewRenderData* outData,
    std::unordered_map<std::string, int>* slotMap
) {
    if (texturePath.empty() || outData == nullptr || slotMap == nullptr) {
        return -1;
    }

    const auto found = slotMap->find(texturePath);
    if (found != slotMap->end()) {
        return found->second;
    }

    const int newSlot = static_cast<int>(outData->texturePaths.size());
    outData->texturePaths.push_back(texturePath);
    (*slotMap)[texturePath] = newSlot;
    return newSlot;
}

bool parseModelFile(const std::string& modelPath, ModelParseResult* outResult, std::string* outError) {
    if (outResult == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: model output buffer is null.";
        }
        return false;
    }

    const ModelFileType modelFileType = detectModelFileType(modelPath);
    if (modelFileType == ModelFileType::Unknown) {
        if (outError != nullptr) {
            *outError = "unsupported model extension; expected .pmd or .pmx.";
        }
        return false;
    }

    if (modelFileType == ModelFileType::Pmd) {
        saba::PMDFile pmdFile;
        if (!saba::ReadPMDFile(&pmdFile, modelPath.c_str())) {
            if (outError != nullptr) {
                *outError = "failed to parse PMD file: " + modelPath;
            }
            return false;
        }

        outResult->fileType = modelFileType;
        outResult->modelName = pmdFile.m_header.m_modelName.ToUtf8String();
        outResult->vertexCount = static_cast<int64_t>(pmdFile.m_vertices.size());
        outResult->faceCount = static_cast<int64_t>(pmdFile.m_faces.size());
        outResult->materialCount = static_cast<int64_t>(pmdFile.m_materials.size());
        outResult->boneCount = static_cast<int64_t>(pmdFile.m_bones.size());
        outResult->morphCount = static_cast<int64_t>(pmdFile.m_morphs.size());
        outResult->rigidBodyCount = static_cast<int64_t>(pmdFile.m_rigidBodies.size());
        outResult->jointCount = static_cast<int64_t>(pmdFile.m_joints.size());
        return true;
    }

    saba::PMXFile pmxFile;
    if (!saba::ReadPMXFile(&pmxFile, modelPath.c_str())) {
        if (outError != nullptr) {
            *outError = "failed to parse PMX file: " + modelPath;
        }
        return false;
    }

    outResult->fileType = modelFileType;
    outResult->modelName = pmxFile.m_info.m_modelName;
    outResult->vertexCount = static_cast<int64_t>(pmxFile.m_vertices.size());
    outResult->faceCount = static_cast<int64_t>(pmxFile.m_faces.size());
    outResult->materialCount = static_cast<int64_t>(pmxFile.m_materials.size());
    outResult->boneCount = static_cast<int64_t>(pmxFile.m_bones.size());
    outResult->morphCount = static_cast<int64_t>(pmxFile.m_morphs.size());
    outResult->rigidBodyCount = static_cast<int64_t>(pmxFile.m_rigidbodies.size());
    outResult->jointCount = static_cast<int64_t>(pmxFile.m_joints.size());
    return true;
}

bool parseMotionFile(const std::string& motionPath, MotionParseResult* outResult, std::string* outError) {
    if (outResult == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: motion output buffer is null.";
        }
        return false;
    }

    const std::string extension = getFileExtension(motionPath);
    if (extension != "vmd") {
        if (outError != nullptr) {
            *outError = "unsupported motion extension; expected .vmd.";
        }
        return false;
    }

    saba::VMDFile vmdFile;
    if (!saba::ReadVMDFile(&vmdFile, motionPath.c_str())) {
        if (outError != nullptr) {
            *outError = "failed to parse VMD file: " + motionPath;
        }
        return false;
    }

    outResult->modelName = vmdFile.m_header.m_modelName.ToUtf8String();
    outResult->motionCount = static_cast<int64_t>(vmdFile.m_motions.size());
    outResult->morphCount = static_cast<int64_t>(vmdFile.m_morphs.size());
    outResult->cameraCount = static_cast<int64_t>(vmdFile.m_cameras.size());
    outResult->lightCount = static_cast<int64_t>(vmdFile.m_lights.size());
    outResult->shadowCount = static_cast<int64_t>(vmdFile.m_shadows.size());
    outResult->ikCount = static_cast<int64_t>(vmdFile.m_iks.size());
    return true;
}

bool buildPreviewRenderDataFromPmd(
    const std::string& modelPath,
    PreviewRenderData* outData,
    std::string* outError
) {
    saba::PMDFile pmdFile;
    if (!saba::ReadPMDFile(&pmdFile, modelPath.c_str())) {
        if (outError != nullptr) {
            *outError = "failed to parse PMD file: " + modelPath;
        }
        return false;
    }

    if (pmdFile.m_faces.size() > kMaxPreviewTriangles) {
        if (outError != nullptr) {
            *outError = "model is too complex for preview renderer (PMD face count limit exceeded).";
        }
        return false;
    }

    const std::string baseDir = getParentDirectory(normalizePathSeparators(modelPath));

    outData->vertices.clear();
    outData->batches.clear();
    outData->texturePaths.clear();
    outData->vertexIndices.clear();

    outData->vertices.reserve(pmdFile.m_faces.size() * 3 * kPreviewVertexStride);
    outData->vertexIndices.reserve(pmdFile.m_faces.size() * 3);

    std::unordered_map<std::string, int> textureSlotMap;
    size_t faceCursor = 0;

    for (const auto& material : pmdFile.m_materials) {
        const size_t startVertex = outData->vertices.size() / kPreviewVertexStride;

        std::string textureName = material.m_textureName.ToUtf8String();
        const size_t separatorPosition = textureName.find('*');
        if (separatorPosition != std::string::npos) {
            textureName = textureName.substr(0, separatorPosition);
        }

        std::string texturePath = normalizeTexturePath(baseDir, textureName);
        if (!isSupportedDiffuseTextureExtension(texturePath)) {
            texturePath.clear();
        }
        const int textureSlot = getOrCreateTextureSlot(texturePath, outData, &textureSlotMap);

        const size_t triangleCount = static_cast<size_t>(material.m_faceVertexCount / 3);
        for (size_t triangleIndex = 0; triangleIndex < triangleCount && faceCursor < pmdFile.m_faces.size(); ++triangleIndex, ++faceCursor) {
            const auto& face = pmdFile.m_faces[faceCursor];
            for (int corner = 0; corner < 3; ++corner) {
                const uint16_t vertexIndex = face.m_vertices[corner];
                if (vertexIndex >= pmdFile.m_vertices.size()) {
                    if (outError != nullptr) {
                        *outError = "invalid PMD face index at face " + std::to_string(faceCursor);
                    }
                    return false;
                }

                const auto& vertex = pmdFile.m_vertices[vertexIndex];
                appendPreviewVertex(&outData->vertices, vertex.m_position, vertex.m_normal, vertex.m_uv);
                outData->vertexIndices.push_back(static_cast<uint32_t>(vertexIndex));
            }
        }

        const size_t endVertex = outData->vertices.size() / kPreviewVertexStride;
        if (!appendBatch(&outData->batches, startVertex, endVertex - startVertex, textureSlot, outError)) {
            return false;
        }
    }

    if (faceCursor < pmdFile.m_faces.size()) {
        const size_t startVertex = outData->vertices.size() / kPreviewVertexStride;
        for (; faceCursor < pmdFile.m_faces.size(); ++faceCursor) {
            const auto& face = pmdFile.m_faces[faceCursor];
            for (int corner = 0; corner < 3; ++corner) {
                const uint16_t vertexIndex = face.m_vertices[corner];
                if (vertexIndex >= pmdFile.m_vertices.size()) {
                    if (outError != nullptr) {
                        *outError = "invalid PMD face index at face " + std::to_string(faceCursor);
                    }
                    return false;
                }

                const auto& vertex = pmdFile.m_vertices[vertexIndex];
                appendPreviewVertex(&outData->vertices, vertex.m_position, vertex.m_normal, vertex.m_uv);
                outData->vertexIndices.push_back(static_cast<uint32_t>(vertexIndex));
            }
        }

        const size_t endVertex = outData->vertices.size() / kPreviewVertexStride;
        if (!appendBatch(&outData->batches, startVertex, endVertex - startVertex, -1, outError)) {
            return false;
        }
    }

    return true;
}

bool buildPreviewRenderDataFromPmx(
    const std::string& modelPath,
    PreviewRenderData* outData,
    std::string* outError
) {
    saba::PMXFile pmxFile;
    if (!saba::ReadPMXFile(&pmxFile, modelPath.c_str())) {
        if (outError != nullptr) {
            *outError = "failed to parse PMX file: " + modelPath;
        }
        return false;
    }

    if (pmxFile.m_faces.size() > kMaxPreviewTriangles) {
        if (outError != nullptr) {
            *outError = "model is too complex for preview renderer (PMX face count limit exceeded).";
        }
        return false;
    }

    const std::string baseDir = getParentDirectory(normalizePathSeparators(modelPath));

    outData->vertices.clear();
    outData->batches.clear();
    outData->texturePaths.clear();
    outData->vertexIndices.clear();

    outData->vertices.reserve(pmxFile.m_faces.size() * 3 * kPreviewVertexStride);
    outData->vertexIndices.reserve(pmxFile.m_faces.size() * 3);

    std::unordered_map<std::string, int> textureSlotMap;
    size_t faceCursor = 0;

    for (const auto& material : pmxFile.m_materials) {
        const size_t startVertex = outData->vertices.size() / kPreviewVertexStride;

        std::string texturePath;
        if (material.m_textureIndex >= 0 && material.m_textureIndex < static_cast<int32_t>(pmxFile.m_textures.size())) {
            texturePath = normalizeTexturePath(baseDir, pmxFile.m_textures[material.m_textureIndex].m_textureName);
            if (!isSupportedDiffuseTextureExtension(texturePath)) {
                texturePath.clear();
            }
        }
        const int textureSlot = getOrCreateTextureSlot(texturePath, outData, &textureSlotMap);

        const size_t triangleCount = static_cast<size_t>(material.m_numFaceVertices / 3);
        for (size_t triangleIndex = 0; triangleIndex < triangleCount && faceCursor < pmxFile.m_faces.size(); ++triangleIndex, ++faceCursor) {
            const auto& face = pmxFile.m_faces[faceCursor];
            for (int corner = 0; corner < 3; ++corner) {
                const uint32_t vertexIndex = face.m_vertices[corner];
                if (vertexIndex >= pmxFile.m_vertices.size()) {
                    if (outError != nullptr) {
                        *outError = "invalid PMX face index at face " + std::to_string(faceCursor);
                    }
                    return false;
                }

                const auto& vertex = pmxFile.m_vertices[vertexIndex];
                appendPreviewVertex(&outData->vertices, vertex.m_position, vertex.m_normal, vertex.m_uv);
                outData->vertexIndices.push_back(vertexIndex);
            }
        }

        const size_t endVertex = outData->vertices.size() / kPreviewVertexStride;
        if (!appendBatch(&outData->batches, startVertex, endVertex - startVertex, textureSlot, outError)) {
            return false;
        }
    }

    if (faceCursor < pmxFile.m_faces.size()) {
        const size_t startVertex = outData->vertices.size() / kPreviewVertexStride;
        for (; faceCursor < pmxFile.m_faces.size(); ++faceCursor) {
            const auto& face = pmxFile.m_faces[faceCursor];
            for (int corner = 0; corner < 3; ++corner) {
                const uint32_t vertexIndex = face.m_vertices[corner];
                if (vertexIndex >= pmxFile.m_vertices.size()) {
                    if (outError != nullptr) {
                        *outError = "invalid PMX face index at face " + std::to_string(faceCursor);
                    }
                    return false;
                }

                const auto& vertex = pmxFile.m_vertices[vertexIndex];
                appendPreviewVertex(&outData->vertices, vertex.m_position, vertex.m_normal, vertex.m_uv);
                outData->vertexIndices.push_back(vertexIndex);
            }
        }

        const size_t endVertex = outData->vertices.size() / kPreviewVertexStride;
        if (!appendBatch(&outData->batches, startVertex, endVertex - startVertex, -1, outError)) {
            return false;
        }
    }

    return true;
}

bool buildPreviewRenderData(const std::string& modelPath, PreviewRenderData* outData, std::string* outError) {
    if (outData == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: preview render data output buffer is null.";
        }
        return false;
    }

    const ModelFileType modelFileType = detectModelFileType(modelPath);
    if (modelFileType == ModelFileType::Unknown) {
        if (outError != nullptr) {
            *outError = "unsupported model extension; expected .pmd or .pmx.";
        }
        return false;
    }

    if (modelFileType == ModelFileType::Pmd) {
        if (!buildPreviewRenderDataFromPmd(modelPath, outData, outError)) {
            return false;
        }
    } else {
        if (!buildPreviewRenderDataFromPmx(modelPath, outData, outError)) {
            return false;
        }
    }

    if (outData->vertices.empty()) {
        if (outError != nullptr) {
            *outError = "preview mesh is empty.";
        }
        return false;
    }

    if (outData->batches.empty()) {
        if (outError != nullptr) {
            *outError = "preview batch list is empty.";
        }
        return false;
    }

    if (outData->vertexIndices.size() * kPreviewVertexStride != outData->vertices.size()) {
        if (outError != nullptr) {
            *outError = "preview mesh index mapping size is inconsistent.";
        }
        return false;
    }

    return true;
}

bool getPreviewRenderDataCached(const std::string& modelPath, PreviewRenderData* outData, std::string* outError) {
    if (outData == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: preview render data output buffer is null.";
        }
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(gPreviewCacheMutex);
        if (gPreviewCache.modelPath == modelPath && !gPreviewCache.data.vertices.empty()) {
            *outData = gPreviewCache.data;
            return true;
        }
    }

    PreviewRenderData newData;
    if (!buildPreviewRenderData(modelPath, &newData, outError)) {
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(gPreviewCacheMutex);
        gPreviewCache.modelPath = modelPath;
        gPreviewCache.data = std::move(newData);
        *outData = gPreviewCache.data;
    }

    return true;
}

bool readMotionMaxFrame(
    const std::string& motionPath,
    int32_t* outMaxFrame,
    std::string* outError
) {
    if (outMaxFrame == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: max motion frame output buffer is null.";
        }
        return false;
    }

    const std::string extension = getFileExtension(motionPath);
    if (extension != "vmd") {
        if (outError != nullptr) {
            *outError = "unsupported motion extension; expected .vmd.";
        }
        return false;
    }

    saba::VMDFile vmdFile;
    if (!saba::ReadVMDFile(&vmdFile, motionPath.c_str())) {
        if (outError != nullptr) {
            *outError = "failed to parse VMD file: " + motionPath;
        }
        return false;
    }

    uint32_t maxFrame = 0;
    for (const auto& motion : vmdFile.m_motions) {
        maxFrame = std::max(maxFrame, motion.m_frame);
    }
    for (const auto& morph : vmdFile.m_morphs) {
        maxFrame = std::max(maxFrame, morph.m_frame);
    }

    *outMaxFrame = static_cast<int32_t>(maxFrame);
    return true;
}

bool createMmdModelForAnimation(
    const std::string& modelPath,
    std::shared_ptr<saba::MMDModel>* outModel,
    std::string* outError
) {
    if (outModel == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: model output buffer is null.";
        }
        return false;
    }

    const ModelFileType modelFileType = detectModelFileType(modelPath);
    const std::string modelDir = getParentDirectory(normalizePathSeparators(modelPath));

    if (modelFileType == ModelFileType::Pmd) {
        auto pmdModel = std::make_shared<saba::PMDModel>();
        if (!pmdModel->Load(modelPath, modelDir)) {
            if (outError != nullptr) {
                *outError = "failed to load PMD model for animation: " + modelPath;
            }
            return false;
        }
        *outModel = pmdModel;
        return true;
    }

    if (modelFileType == ModelFileType::Pmx) {
        auto pmxModel = std::make_shared<saba::PMXModel>();
        if (!pmxModel->Load(modelPath, modelDir)) {
            if (outError != nullptr) {
                *outError = "failed to load PMX model for animation: " + modelPath;
            }
            return false;
        }
        *outModel = pmxModel;
        return true;
    }

    if (outError != nullptr) {
        *outError = "unsupported model extension; expected .pmd or .pmx.";
    }
    return false;
}

bool loadAnimatedRuntimeCache(
    const std::string& modelPath,
    const std::string& motionPath,
    AnimatedRuntimeCache* outCache,
    std::string* outError
) {
    if (outCache == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: animation cache output buffer is null.";
        }
        return false;
    }

    PreviewRenderData previewData;
    if (!getPreviewRenderDataCached(modelPath, &previewData, outError)) {
        return false;
    }

    if (previewData.vertexIndices.empty()) {
        if (outError != nullptr) {
            *outError = "preview mesh index mapping is empty.";
        }
        return false;
    }

    std::shared_ptr<saba::MMDModel> model;
    if (!createMmdModelForAnimation(modelPath, &model, outError)) {
        return false;
    }

    saba::VMDFile vmdFile;
    if (!saba::ReadVMDFile(&vmdFile, motionPath.c_str())) {
        if (outError != nullptr) {
            *outError = "failed to parse VMD file: " + motionPath;
        }
        return false;
    }

    auto animation = std::make_unique<saba::VMDAnimation>();
    if (!animation->Create(model)) {
        if (outError != nullptr) {
            *outError = "failed to initialize VMD animation controller for model.";
        }
        return false;
    }

    if (!animation->Add(vmdFile)) {
        if (outError != nullptr) {
            *outError = "failed to bind VMD data to model animation controller.";
        }
        return false;
    }

    model->InitializeAnimation();
    model->BeginAnimation();
    model->UpdateAllAnimation(animation.get(), 0.0f, 1.0f / 60.0f);
    model->EndAnimation();
    model->Update();

    outCache->modelPath = modelPath;
    outCache->motionPath = motionPath;
    outCache->model = std::move(model);
    outCache->animation = std::move(animation);
    outCache->previewVertexIndices = std::move(previewData.vertexIndices);
    outCache->animatedVertices.resize(outCache->previewVertexIndices.size() * kPreviewVertexStride);
    outCache->maxMotionFrame = static_cast<int32_t>(outCache->animation->GetMaxKeyTime());

    LOGI(
        "Animated runtime cache loaded. model=%s motion=%s mappedVertices=%zu",
        modelPath.c_str(),
        motionPath.c_str(),
        outCache->previewVertexIndices.size()
    );

    return true;
}

bool ensureAnimatedRuntimeCacheLocked(
    const std::string& modelPath,
    const std::string& motionPath,
    std::string* outError
) {
    const bool cacheHit =
        gAnimatedRuntimeCache.modelPath == modelPath &&
        gAnimatedRuntimeCache.motionPath == motionPath &&
        gAnimatedRuntimeCache.model != nullptr &&
        gAnimatedRuntimeCache.animation != nullptr &&
        !gAnimatedRuntimeCache.previewVertexIndices.empty();

    if (cacheHit) {
        return true;
    }

    AnimatedRuntimeCache newCache;
    if (!loadAnimatedRuntimeCache(modelPath, motionPath, &newCache, outError)) {
        return false;
    }

    gAnimatedRuntimeCache = std::move(newCache);
    return true;
}

bool sampleAnimatedPreviewVerticesLocked(
    float frame,
    const float** outVertexData,
    size_t* outFloatCount,
    int32_t* outMaxMotionFrame,
    std::string* outError
) {
    if (outVertexData == nullptr || outFloatCount == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: animated preview pointer outputs are null.";
        }
        return false;
    }

    auto* animation = gAnimatedRuntimeCache.animation.get();
    const auto& model = gAnimatedRuntimeCache.model;
    const auto& previewVertexIndices = gAnimatedRuntimeCache.previewVertexIndices;

    if (animation == nullptr || model == nullptr) {
        if (outError != nullptr) {
            *outError = "animated runtime cache is not initialized.";
        }
        return false;
    }

    if (previewVertexIndices.empty()) {
        if (outError != nullptr) {
            *outError = "animated preview vertex mapping is empty.";
        }
        return false;
    }

    const float safeFrame = frame < 0.0f ? 0.0f : frame;
    model->BeginAnimation();
    model->UpdateAllAnimation(animation, safeFrame, 1.0f / 60.0f);
    model->EndAnimation();
    model->Update();

    const auto* positions = model->GetUpdatePositions();
    const auto* normals = model->GetUpdateNormals();
    const auto* uvs = model->GetUpdateUVs();
    const size_t modelVertexCount = model->GetVertexCount();

    if (positions == nullptr || normals == nullptr || uvs == nullptr || modelVertexCount == 0) {
        if (outError != nullptr) {
            *outError = "animated model has no renderable vertices.";
        }
        return false;
    }

    auto& animatedVertices = gAnimatedRuntimeCache.animatedVertices;
    const size_t expectedFloatCount = previewVertexIndices.size() * kPreviewVertexStride;
    if (animatedVertices.size() != expectedFloatCount) {
        animatedVertices.resize(expectedFloatCount);
    }

    size_t invalidVertexCount = 0;
    for (size_t index = 0; index < previewVertexIndices.size(); ++index) {
        const uint32_t sourceIndex = previewVertexIndices[index];
        if (sourceIndex >= modelVertexCount) {
            if (outError != nullptr) {
                *outError = "animated preview source index out of range.";
            }
            return false;
        }

        const auto& position = positions[sourceIndex];
        const auto& normal = normals[sourceIndex];
        const auto& uv = uvs[sourceIndex];
        const size_t base = index * kPreviewVertexStride;

        const bool validPosition =
            std::isfinite(position.x) &&
            std::isfinite(position.y) &&
            std::isfinite(position.z) &&
            std::fabs(position.x) < 100000.0f &&
            std::fabs(position.y) < 100000.0f &&
            std::fabs(position.z) < 100000.0f;
        const bool validNormal =
            std::isfinite(normal.x) &&
            std::isfinite(normal.y) &&
            std::isfinite(normal.z) &&
            std::fabs(normal.x) < 1000.0f &&
            std::fabs(normal.y) < 1000.0f &&
            std::fabs(normal.z) < 1000.0f;
        const bool validUv =
            std::isfinite(uv.x) &&
            std::isfinite(uv.y) &&
            std::fabs(uv.x) < 1000.0f &&
            std::fabs(uv.y) < 1000.0f;

        if (!(validPosition && validNormal && validUv)) {
            invalidVertexCount += 1;
            continue;
        }

        animatedVertices[base] = position.x;
        animatedVertices[base + 1] = position.y;
        animatedVertices[base + 2] = position.z;
        animatedVertices[base + 3] = normal.x;
        animatedVertices[base + 4] = normal.y;
        animatedVertices[base + 5] = normal.z;
        animatedVertices[base + 6] = uv.x;
        animatedVertices[base + 7] = uv.y;
    }

    if (invalidVertexCount >= previewVertexIndices.size()) {
        if (outError != nullptr) {
            *outError = "animated preview produced only invalid vertices.";
        }
        return false;
    }

    if (invalidVertexCount > 0) {
        LOGE(
            "sampleAnimatedPreviewVerticesLocked filtered %zu invalid vertices for stability.",
            invalidVertexCount
        );
    }

    *outVertexData = animatedVertices.data();
    *outFloatCount = animatedVertices.size();

    if (outMaxMotionFrame != nullptr) {
        *outMaxMotionFrame = gAnimatedRuntimeCache.maxMotionFrame;
    }

    return true;
}

bool resolveAutoAnimationSampledFrame(
    const std::string& modelPath,
    const std::string& motionPath,
    bool isLooping,
    bool restart,
    float* outSampledFrame,
    int32_t* outMaxFrame,
    std::string* outError
) {
    if (outSampledFrame == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: sampled frame output buffer is null.";
        }
        return false;
    }

    if (modelPath.empty()) {
        if (outError != nullptr) {
            *outError = "model path is empty.";
        }
        return false;
    }

    if (motionPath.empty()) {
        if (outError != nullptr) {
            *outError = "motion path is empty.";
        }
        return false;
    }

    int32_t maxFrame = 0;
    {
        std::lock_guard<std::mutex> clockLock(gAutoAnimationClockMutex);

        const auto cachedMaxFrame = gMotionMaxFrameCache.find(motionPath);
        if (cachedMaxFrame != gMotionMaxFrameCache.end()) {
            maxFrame = cachedMaxFrame->second;
        }

        const bool shouldRestartClock =
            restart ||
            !gAutoAnimationClockStarted ||
            gAutoAnimationModelPath != modelPath ||
            gAutoAnimationMotionPath != motionPath ||
            gAutoAnimationLooping != isLooping;
        if (shouldRestartClock) {
            gAutoAnimationModelPath = modelPath;
            gAutoAnimationMotionPath = motionPath;
            gAutoAnimationLooping = isLooping;
            gAutoAnimationClockStarted = true;
            gAutoAnimationStartedAt = std::chrono::steady_clock::now();
        }
    }

    if (maxFrame <= 0) {
        int32_t parsedMaxFrame = 0;
        if (!readMotionMaxFrame(motionPath, &parsedMaxFrame, outError)) {
            return false;
        }

        maxFrame = std::max(parsedMaxFrame, 0);
        std::lock_guard<std::mutex> clockLock(gAutoAnimationClockMutex);
        gMotionMaxFrameCache[motionPath] = maxFrame;
    }

    {
        std::lock_guard<std::mutex> clockLock(gAutoAnimationClockMutex);
        const auto now = std::chrono::steady_clock::now();
        const float elapsedSeconds = std::chrono::duration<float>(now - gAutoAnimationStartedAt).count();
        const float elapsedFrames = std::max(elapsedSeconds, 0.0f) * 30.0f;
        const float maxFrameFloat = static_cast<float>(maxFrame);

        if (maxFrameFloat <= 1.0f) {
            *outSampledFrame = 0.0f;
        } else if (isLooping) {
            *outSampledFrame = std::fmod(elapsedFrames, maxFrameFloat + 1.0f);
        } else {
            *outSampledFrame = std::min(elapsedFrames, maxFrameFloat);
        }
    }

    if (outMaxFrame != nullptr) {
        *outMaxFrame = maxFrame;
    }

    return true;
}

bool buildAnimatedPreviewMesh(
    const std::string& modelPath,
    const std::string& motionPath,
    float frame,
    std::vector<float>* outVertices,
    int32_t* outMaxMotionFrame,
    std::string* outError
) {
    if (outVertices == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: animated preview output buffer is null.";
        }
        return false;
    }

    if (modelPath.empty()) {
        if (outError != nullptr) {
            *outError = "model path is empty.";
        }
        return false;
    }

    if (motionPath.empty()) {
        if (outError != nullptr) {
            *outError = "motion path is empty.";
        }
        return false;
    }

    std::lock_guard<std::mutex> lock(gAnimatedRuntimeCacheMutex);
    if (!ensureAnimatedRuntimeCacheLocked(modelPath, motionPath, outError)) {
        return false;
    }

    const float* cachedVertexData = nullptr;
    size_t cachedFloatCount = 0;
    if (!sampleAnimatedPreviewVerticesLocked(frame, &cachedVertexData, &cachedFloatCount, outMaxMotionFrame, outError)) {
        return false;
    }

    outVertices->assign(cachedVertexData, cachedVertexData + cachedFloatCount);
    return true;
}

bool buildAnimatedPreviewMeshAuto(
    const std::string& modelPath,
    const std::string& motionPath,
    bool isLooping,
    bool restart,
    std::vector<float>* outVertices,
    int32_t* outMaxMotionFrame,
    std::string* outError
) {
    if (outVertices == nullptr) {
        if (outError != nullptr) {
            *outError = "internal error: animated preview output buffer is null.";
        }
        return false;
    }

    float sampledFrame = 0.0f;
    if (!resolveAutoAnimationSampledFrame(
            modelPath,
            motionPath,
            isLooping,
            restart,
            &sampledFrame,
            nullptr,
            outError)) {
        return false;
    }

    return buildAnimatedPreviewMesh(
        modelPath,
        motionPath,
        sampledFrame,
        outVertices,
        outMaxMotionFrame,
        outError
    );
}

bool decodeImageRgbaCached(
    const std::string& imagePath,
    int* outWidth,
    int* outHeight,
    std::vector<uint8_t>* outPixels,
    std::string* outError
) {
    if (imagePath.empty()) {
        if (outError != nullptr) {
            *outError = "image path is empty.";
        }
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(gImageDecodeCacheMutex);
        if (gImageDecodeCache.imagePath == imagePath &&
            gImageDecodeCache.width > 0 &&
            gImageDecodeCache.height > 0 &&
            !gImageDecodeCache.rgbaPixels.empty()) {
            if (outWidth != nullptr) {
                *outWidth = gImageDecodeCache.width;
            }
            if (outHeight != nullptr) {
                *outHeight = gImageDecodeCache.height;
            }
            if (outPixels != nullptr) {
                *outPixels = gImageDecodeCache.rgbaPixels;
            }
            return true;
        }
    }

    int width = 0;
    int height = 0;
    int channels = 0;
    stbi_uc* decodedPixels = stbi_load(imagePath.c_str(), &width, &height, &channels, 4);
    if (decodedPixels == nullptr) {
        if (outError != nullptr) {
            const char* reason = stbi_failure_reason();
            *outError = "failed to decode image: " + imagePath + (reason != nullptr ? (" (" + std::string(reason) + ")") : "");
        }
        return false;
    }

    if (width <= 0 || height <= 0) {
        stbi_image_free(decodedPixels);
        if (outError != nullptr) {
            *outError = "decoded image size is invalid: " + imagePath;
        }
        return false;
    }

    const size_t totalSize = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    if (totalSize == 0) {
        stbi_image_free(decodedPixels);
        if (outError != nullptr) {
            *outError = "decoded image pixel count is zero: " + imagePath;
        }
        return false;
    }

    std::vector<uint8_t> rgbaPixels(decodedPixels, decodedPixels + totalSize);
    stbi_image_free(decodedPixels);

    if (outWidth != nullptr) {
        *outWidth = width;
    }
    if (outHeight != nullptr) {
        *outHeight = height;
    }
    if (outPixels != nullptr) {
        *outPixels = rgbaPixels;
    }

    {
        std::lock_guard<std::mutex> lock(gImageDecodeCacheMutex);
        gImageDecodeCache.imagePath = imagePath;
        gImageDecodeCache.width = width;
        gImageDecodeCache.height = height;
        gImageDecodeCache.rgbaPixels = std::move(rgbaPixels);
    }

    return true;
}

#endif

jlongArray buildLongArray(JNIEnv* env, std::initializer_list<jlong> values) {
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }

    std::vector<jlong> buffer(values);
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(buffer.size()), buffer.data());
    return result;
}

jintArray buildIntArray(JNIEnv* env, const std::vector<int32_t>& values) {
    if (values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }

    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }

    if (!values.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), reinterpret_cast<const jint*>(values.data()));
    }
    return result;
}

jfloatArray buildFloatArray(JNIEnv* env, const std::vector<float>& values) {
    if (values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }

    if (!values.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

jbyteArray buildByteArray(JNIEnv* env, const std::vector<uint8_t>& values) {
    if (values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }

    if (!values.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(values.size()),
            reinterpret_cast<const jbyte*>(values.data())
        );
    }
    return result;
}

jobjectArray buildStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    for (jsize index = 0; index < static_cast<jsize>(values.size()); ++index) {
        jstring javaString = stringToJString(env, values[static_cast<size_t>(index)]);
        if (javaString == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(result, index, javaString);
        env->DeleteLocalRef(javaString);
    }

    return result;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeIsAvailable(JNIEnv*, jclass) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeGetUnavailableReason(JNIEnv* env, jclass) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    return stringToJString(env, "");
#else
    return stringToJString(env, kUnavailableReason);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeGetLastError(JNIEnv* env, jclass) {
    return stringToJString(env, getLastErrorCopy());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadModelName(JNIEnv* env, jclass, jstring pathModel) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }

    ModelParseResult parsedModel;
    std::string parseError;
    if (!parseModelFile(modelPath, &parsedModel, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    clearLastError();
    return stringToJString(env, parsedModel.modelName);
#else
    setLastError(kUnavailableReason);
    (void) pathModel;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadModelSummary(JNIEnv* env, jclass, jstring pathModel) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }

    ModelParseResult parsedModel;
    std::string parseError;
    if (!parseModelFile(modelPath, &parsedModel, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    clearLastError();
    return buildLongArray(
        env,
        {
            static_cast<jlong>(toModelFormatId(parsedModel.fileType)),
            static_cast<jlong>(parsedModel.vertexCount),
            static_cast<jlong>(parsedModel.faceCount),
            static_cast<jlong>(parsedModel.materialCount),
            static_cast<jlong>(parsedModel.boneCount),
            static_cast<jlong>(parsedModel.morphCount),
            static_cast<jlong>(parsedModel.rigidBodyCount),
            static_cast<jlong>(parsedModel.jointCount),
        }
    );
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadMotionModelName(JNIEnv* env, jclass, jstring pathMotion) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string motionPath = jstringToString(env, pathMotion);
    if (motionPath.empty()) {
        setLastError("motion path is empty.");
        return nullptr;
    }

    MotionParseResult parsedMotion;
    std::string parseError;
    if (!parseMotionFile(motionPath, &parsedMotion, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    clearLastError();
    return stringToJString(env, parsedMotion.modelName);
#else
    setLastError(kUnavailableReason);
    (void) pathMotion;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadMotionSummary(JNIEnv* env, jclass, jstring pathMotion) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string motionPath = jstringToString(env, pathMotion);
    if (motionPath.empty()) {
        setLastError("motion path is empty.");
        return nullptr;
    }

    MotionParseResult parsedMotion;
    std::string parseError;
    if (!parseMotionFile(motionPath, &parsedMotion, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    clearLastError();
    return buildLongArray(
        env,
        {
            static_cast<jlong>(parsedMotion.motionCount),
            static_cast<jlong>(parsedMotion.morphCount),
            static_cast<jlong>(parsedMotion.cameraCount),
            static_cast<jlong>(parsedMotion.lightCount),
            static_cast<jlong>(parsedMotion.shadowCount),
            static_cast<jlong>(parsedMotion.ikCount),
        }
    );
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathMotion;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadMotionMaxFrame(JNIEnv* env, jclass, jstring pathMotion) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string motionPath = jstringToString(env, pathMotion);
    if (motionPath.empty()) {
        setLastError("motion path is empty.");
        return -1;
    }

    int32_t maxFrame = 0;
    std::string parseError;
    if (!readMotionMaxFrame(motionPath, &maxFrame, &parseError)) {
        setLastError(parseError);
        return -1;
    }

    clearLastError();
    return static_cast<jint>(maxFrame);
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathMotion;
    return -1;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeBuildPreviewAnimatedMesh(JNIEnv* env, jclass, jstring pathModel, jstring pathMotion, jfloat frame) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    const std::string motionPath = jstringToString(env, pathMotion);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }
    if (motionPath.empty()) {
        setLastError("motion path is empty.");
        return nullptr;
    }

    std::vector<float> animatedVertices;
    std::string parseError;
    if (!buildAnimatedPreviewMesh(modelPath, motionPath, static_cast<float>(frame), &animatedVertices, nullptr, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    jfloatArray result = buildFloatArray(env, animatedVertices);
    if (result == nullptr) {
        setLastError("failed to allocate JNI float array for animated preview mesh.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    (void) pathMotion;
    (void) frame;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeBuildPreviewAnimatedMeshAuto(
    JNIEnv* env,
    jclass,
    jstring pathModel,
    jstring pathMotion,
    jboolean isLooping,
    jboolean restart
) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    const std::string motionPath = jstringToString(env, pathMotion);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }
    if (motionPath.empty()) {
        setLastError("motion path is empty.");
        return nullptr;
    }

    std::vector<float> animatedVertices;
    std::string parseError;
    if (!buildAnimatedPreviewMeshAuto(
            modelPath,
            motionPath,
            isLooping == JNI_TRUE,
            restart == JNI_TRUE,
            &animatedVertices,
            nullptr,
            &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    jfloatArray result = buildFloatArray(env, animatedVertices);
    if (result == nullptr) {
        setLastError("failed to allocate JNI float array for animated preview mesh.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    (void) pathMotion;
    (void) isLooping;
    (void) restart;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeRenderPreviewFrame(
    JNIEnv* env,
    jclass,
    jstring pathModel,
    jstring pathMotion,
    jboolean isLooping,
    jboolean restart,
    jfloat rotationX,
    jfloat rotationY,
    jfloat rotationZ,
    jfloat centerX,
    jfloat centerY,
    jfloat centerZ,
    jfloat fitScale,
    jfloat cameraDistance,
    jfloat cameraTargetHeight,
    jfloat aspectRatio,
    jfloat nearClip,
    jfloat farClip,
    jobject vertexBuffer,
    jint vertexCount,
    jobject drawBatchData,
    jobject textureIdsBySlot,
    jint program,
    jint positionHandle,
    jint normalHandle,
    jint texCoordHandle,
    jint mvpHandle,
    jint modelHandle,
    jint useTextureHandle,
    jint textureSamplerHandle
) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    const std::string motionPath = jstringToString(env, pathMotion);

    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (program <= 0 ||
        positionHandle < 0 ||
        normalHandle < 0 ||
        texCoordHandle < 0 ||
        mvpHandle < 0 ||
        modelHandle < 0 ||
        useTextureHandle < 0 ||
        textureSamplerHandle < 0) {
        clearLastError();
        return JNI_TRUE;
    }

    if (modelPath.empty() || vertexBuffer == nullptr || vertexCount <= 0) {
        clearLastError();
        return JNI_TRUE;
    }

    auto* baseVertexData = static_cast<float*>(env->GetDirectBufferAddress(vertexBuffer));
    if (baseVertexData == nullptr) {
        setLastError("failed to access direct vertex buffer for native preview rendering.");
        return JNI_FALSE;
    }

    const size_t expectedFloatCount = static_cast<size_t>(vertexCount) * kPreviewVertexStride;
    const float* drawVertexData = baseVertexData;
    std::unique_lock<std::mutex> animatedRuntimeLock;

    if (!motionPath.empty()) {
        float sampledFrame = 0.0f;
        std::string parseError;
        if (!resolveAutoAnimationSampledFrame(
                modelPath,
                motionPath,
                isLooping == JNI_TRUE,
                restart == JNI_TRUE,
                &sampledFrame,
                nullptr,
                &parseError)) {
            setLastError(parseError);
            return JNI_FALSE;
        }

        animatedRuntimeLock = std::unique_lock<std::mutex>(gAnimatedRuntimeCacheMutex);
        if (!ensureAnimatedRuntimeCacheLocked(modelPath, motionPath, &parseError)) {
            setLastError(parseError);
            return JNI_FALSE;
        }

        size_t animatedFloatCount = 0;
        if (!sampleAnimatedPreviewVerticesLocked(
                sampledFrame,
                &drawVertexData,
                &animatedFloatCount,
                nullptr,
                &parseError)) {
            setLastError(parseError);
            return JNI_FALSE;
        }

        if (drawVertexData == nullptr || animatedFloatCount != expectedFloatCount) {
            setLastError("animated preview mesh size mismatches current vertex buffer size.");
            return JNI_FALSE;
        }
    }

    const float safeAspect = aspectRatio > 0.0f ? aspectRatio : 1.0f;
    const float safeNear = nearClip > 0.0f ? nearClip : 0.01f;
    const float safeFar = farClip > safeNear ? farClip : (safeNear + 100.0f);

    glm::mat4 modelMat(1.0f);
    modelMat = glm::translate(modelMat, glm::vec3(-centerX, -centerY, -centerZ));
    modelMat = glm::scale(modelMat, glm::vec3(fitScale, fitScale, fitScale));
    modelMat = glm::rotate(modelMat, glm::radians(rotationX), glm::vec3(1.0f, 0.0f, 0.0f));
    modelMat = glm::rotate(modelMat, glm::radians(rotationY), glm::vec3(0.0f, 1.0f, 0.0f));
    modelMat = glm::rotate(modelMat, glm::radians(rotationZ), glm::vec3(0.0f, 0.0f, 1.0f));

    const glm::mat4 viewMat = glm::lookAt(
        glm::vec3(0.0f, 0.18f + cameraTargetHeight, cameraDistance),
        glm::vec3(0.0f, cameraTargetHeight, 0.0f),
        glm::vec3(0.0f, 1.0f, 0.0f)
    );
    const glm::mat4 projectionMat = glm::perspective(glm::radians(45.0f), safeAspect, safeNear, safeFar);
    const glm::mat4 mvpMat = projectionMat * viewMat * modelMat;

    const auto* batchValues = static_cast<const jint*>(
        drawBatchData != nullptr ? env->GetDirectBufferAddress(drawBatchData) : nullptr
    );
    const auto* textureValues = static_cast<const jint*>(
        textureIdsBySlot != nullptr ? env->GetDirectBufferAddress(textureIdsBySlot) : nullptr
    );

    jsize batchValueCount = 0;
    jsize textureValueCount = 0;

    if (drawBatchData != nullptr) {
        const jlong batchCapacity = env->GetDirectBufferCapacity(drawBatchData);
        if (batchValues == nullptr || batchCapacity <= 0) {
            setLastError("failed to access draw batch direct buffer for native preview rendering.");
            return JNI_FALSE;
        }
        batchValueCount = static_cast<jsize>(batchCapacity);
    }

    if (textureIdsBySlot != nullptr) {
        const jlong textureCapacity = env->GetDirectBufferCapacity(textureIdsBySlot);
        if (textureValues == nullptr || textureCapacity < 0) {
            setLastError("failed to access texture id direct buffer for native preview rendering.");
            return JNI_FALSE;
        }
        textureValueCount = static_cast<jsize>(textureCapacity);
    }

    glUseProgram(static_cast<GLuint>(program));
    glUniformMatrix4fv(mvpHandle, 1, GL_FALSE, glm::value_ptr(mvpMat));
    glUniformMatrix4fv(modelHandle, 1, GL_FALSE, glm::value_ptr(modelMat));

    const GLsizei strideBytes = static_cast<GLsizei>(kPreviewVertexStride * sizeof(float));
    glVertexAttribPointer(
        positionHandle,
        3,
        GL_FLOAT,
        GL_FALSE,
        strideBytes,
        reinterpret_cast<const GLvoid*>(drawVertexData)
    );
    glEnableVertexAttribArray(positionHandle);

    glVertexAttribPointer(
        normalHandle,
        3,
        GL_FLOAT,
        GL_FALSE,
        strideBytes,
        reinterpret_cast<const GLvoid*>(drawVertexData + 3)
    );
    glEnableVertexAttribArray(normalHandle);

    glVertexAttribPointer(
        texCoordHandle,
        2,
        GL_FLOAT,
        GL_FALSE,
        strideBytes,
        reinterpret_cast<const GLvoid*>(drawVertexData + 6)
    );
    glEnableVertexAttribArray(texCoordHandle);

    glActiveTexture(GL_TEXTURE0);
    glUniform1i(textureSamplerHandle, 0);

    GLuint boundTexture = static_cast<GLuint>(-1);
    float lastUseTexture = -1.0f;

    if (batchValues != nullptr && batchValueCount >= 3) {
        for (jsize cursor = 0; cursor + 2 < batchValueCount; cursor += 3) {
            const GLint firstVertex = batchValues[cursor];
            const GLsizei drawVertexCount = static_cast<GLsizei>(batchValues[cursor + 1]);
            const jint textureSlot = batchValues[cursor + 2];

            if (firstVertex < 0 || drawVertexCount <= 0) {
                continue;
            }

            GLuint textureId = 0;
            if (textureValues != nullptr && textureSlot >= 0 && textureSlot < textureValueCount) {
                textureId = static_cast<GLuint>(textureValues[textureSlot]);
            }

            const float useTexture = textureId != 0 ? 1.0f : 0.0f;
            if (useTexture != lastUseTexture) {
                glUniform1f(useTextureHandle, useTexture);
                lastUseTexture = useTexture;
            }

            if (useTexture > 0.5f && textureId != boundTexture) {
                glBindTexture(GL_TEXTURE_2D, textureId);
                boundTexture = textureId;
            }

            glDrawArrays(GL_TRIANGLES, firstVertex, drawVertexCount);
        }
    } else {
        glUniform1f(useTextureHandle, 0.0f);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(vertexCount));
    }

    if (boundTexture != static_cast<GLuint>(-1)) {
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    glDisableVertexAttribArray(positionHandle);
    glDisableVertexAttribArray(normalHandle);
    glDisableVertexAttribArray(texCoordHandle);

    clearLastError();
    return JNI_TRUE;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    (void) pathMotion;
    (void) isLooping;
    (void) restart;
    (void) rotationX;
    (void) rotationY;
    (void) rotationZ;
    (void) centerX;
    (void) centerY;
    (void) centerZ;
    (void) fitScale;
    (void) cameraDistance;
    (void) cameraTargetHeight;
    (void) aspectRatio;
    (void) nearClip;
    (void) farClip;
    (void) vertexBuffer;
    (void) vertexCount;
    (void) drawBatchData;
    (void) textureIdsBySlot;
    (void) program;
    (void) positionHandle;
    (void) normalHandle;
    (void) texCoordHandle;
    (void) mvpHandle;
    (void) modelHandle;
    (void) useTextureHandle;
    (void) textureSamplerHandle;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeBuildPreviewMesh(JNIEnv* env, jclass, jstring pathModel) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }

    PreviewRenderData previewData;
    std::string parseError;
    if (!getPreviewRenderDataCached(modelPath, &previewData, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    jfloatArray result = buildFloatArray(env, previewData.vertices);
    if (result == nullptr) {
        setLastError("failed to allocate JNI float array for preview mesh.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeBuildPreviewBatches(JNIEnv* env, jclass, jstring pathModel) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }

    PreviewRenderData previewData;
    std::string parseError;
    if (!getPreviewRenderDataCached(modelPath, &previewData, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    jintArray result = buildIntArray(env, previewData.batches);
    if (result == nullptr) {
        setLastError("failed to allocate JNI int array for preview batches.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadPreviewTexturePath(JNIEnv* env, jclass, jstring pathModel) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }

    PreviewRenderData previewData;
    std::string parseError;
    if (!getPreviewRenderDataCached(modelPath, &previewData, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    if (previewData.texturePaths.empty()) {
        clearLastError();
        return nullptr;
    }

    clearLastError();
    return stringToJString(env, previewData.texturePaths.front());
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadPreviewTexturePaths(JNIEnv* env, jclass, jstring pathModel) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string modelPath = jstringToString(env, pathModel);
    if (modelPath.empty()) {
        setLastError("model path is empty.");
        return nullptr;
    }

    PreviewRenderData previewData;
    std::string parseError;
    if (!getPreviewRenderDataCached(modelPath, &previewData, &parseError)) {
        setLastError(parseError);
        return nullptr;
    }

    jobjectArray result = buildStringArray(env, previewData.texturePaths);
    if (result == nullptr) {
        setLastError("failed to allocate JNI string array for preview texture paths.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathModel;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeDecodeImageSize(JNIEnv* env, jclass, jstring pathImage) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string imagePath = jstringToString(env, pathImage);
    if (imagePath.empty()) {
        setLastError("image path is empty.");
        return nullptr;
    }

    int width = 0;
    int height = 0;
    std::string decodeError;
    if (!decodeImageRgbaCached(imagePath, &width, &height, nullptr, &decodeError)) {
        setLastError(decodeError);
        return nullptr;
    }

    std::vector<int32_t> sizeData = {
        static_cast<int32_t>(width),
        static_cast<int32_t>(height)
    };

    jintArray result = buildIntArray(env, sizeData);
    if (result == nullptr) {
        setLastError("failed to allocate JNI int array for decoded image size.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathImage;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeDecodeImageRgba(JNIEnv* env, jclass, jstring pathImage) {
#if defined(CUSTARD_HAS_SABA) && CUSTARD_HAS_SABA
    const std::string imagePath = jstringToString(env, pathImage);
    if (imagePath.empty()) {
        setLastError("image path is empty.");
        return nullptr;
    }

    int width = 0;
    int height = 0;
    std::vector<uint8_t> rgbaPixels;
    std::string decodeError;
    if (!decodeImageRgbaCached(imagePath, &width, &height, &rgbaPixels, &decodeError)) {
        setLastError(decodeError);
        return nullptr;
    }

    jbyteArray result = buildByteArray(env, rgbaPixels);
    if (result == nullptr) {
        setLastError("failed to allocate JNI byte array for decoded image rgba.");
        return nullptr;
    }

    clearLastError();
    return result;
#else
    setLastError(kUnavailableReason);
    (void) env;
    (void) pathImage;
    return nullptr;
#endif
}
