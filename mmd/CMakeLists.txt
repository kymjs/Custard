cmake_minimum_required(VERSION 3.22.1)

project("mmd_jni")

set(SABA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/saba")
set(SABA_DIR_FALLBACK "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/saba")

if (EXISTS "${SABA_DIR}/CMakeLists.txt")
    set(CUSTARD_SABA_DIR "${SABA_DIR}")
elseif (EXISTS "${SABA_DIR_FALLBACK}/CMakeLists.txt")
    set(CUSTARD_SABA_DIR "${SABA_DIR_FALLBACK}")
endif()

add_library(
    MmdWrapper
    SHARED
    src/main/cpp/mmd_jni.cpp
)

if (DEFINED CUSTARD_SABA_DIR)
    target_compile_definitions(MmdWrapper PRIVATE CUSTARD_HAS_SABA=1)

    target_include_directories(MmdWrapper PRIVATE
        "${CUSTARD_SABA_DIR}/src"
        "${CUSTARD_SABA_DIR}/external/glm/include"
        "${CUSTARD_SABA_DIR}/external/spdlog/include"
        "${CUSTARD_SABA_DIR}/external/stb/include"
    )

    target_sources(MmdWrapper PRIVATE
        "${CUSTARD_SABA_DIR}/src/Saba/Base/File.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Base/Log.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Base/Path.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Base/Singleton.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Base/UnicodeUtil.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/SjisToUnicode.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/MMDIkSolver.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/MMDMaterial.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/MMDModel.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/MMDMorph.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/MMDNode.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/PMDFile.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/PMDModel.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/PMXFile.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/PMXModel.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/VMDAnimation.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/VMDFile.cpp"
        "${CUSTARD_SABA_DIR}/src/Saba/Model/MMD/VPDFile.cpp"
        "src/main/cpp/mmd_physics_stub.cpp"
    )
else()
    target_compile_definitions(MmdWrapper PRIVATE CUSTARD_HAS_SABA=0)
endif()

target_link_libraries(
    MmdWrapper
    android
    log
    GLESv2
)

# 16KB page size support (Android 15+ requirement)
target_link_options(MmdWrapper PRIVATE "-Wl,-z,max-page-size=16384")
