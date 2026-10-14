cmake_minimum_required(VERSION 3.22.1)
project(bonsai_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------
option(BONSAI_BUILD_LLAMA_SERVER "Cross-build the PrismML fork's llama-server" ON)
option(BONSAI_WITH_LLAMA_JNI     "Experimental in-process JNI engine (requires fork sources)" OFF)

# Path to the PrismML llama.cpp fork checkout.
# Default: <project>/third_party/llama.cpp  (see docs/BUILD.md)
get_filename_component(BONSAI_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
if(NOT LLAMA_DIR)
    set(LLAMA_DIR "${BONSAI_PROJECT_ROOT}/third_party/llama.cpp")
endif()
if(NOT EXISTS "${LLAMA_DIR}")
    message(FATAL_ERROR
        "LLAMA_DIR not found: ${LLAMA_DIR}\n"
        "Clone it first:\n"
        "  git clone -b prism https://github.com/PrismML-Eng/llama.cpp "
        "third_party/llama.cpp")
endif()
message(STATUS "BonsaiDroid: using llama.cpp fork at ${LLAMA_DIR}")

# ---------------------------------------------------------------------------
# JNI telemetry / affinity library
# ---------------------------------------------------------------------------
add_library(bonsai_native SHARED bonsai_jni.cpp)

find_library(log-lib log)
target_link_libraries(bonsai_native PRIVATE ${log-lib})

target_compile_options(bonsai_native PRIVATE -O3 -Wall)
target_link_options(bonsai_native PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# llama-server from the PrismML fork (the on-device inference runtime).
#
# Built as an executable, then POST-INSTALLED into the jniLibs folder named
# `libllamaserver.so` so Android packages/extracts it with an execute bit
# (see app/build.gradle.kts `useLegacyPackaging`).
#
# CPU-first configuration for Snapdragon 8 Elite:
#   * GGML_NATIVE OFF + explicit NEON/dotprod/i8mm flags -> predictable builds
#   * Vulkan left OFF by default; enable per-experiment with BONSAI_ENABLE_VULKAN
# ---------------------------------------------------------------------------
if(BONSAI_BUILD_LLAMA_SERVER)
    set(BONSAI_ENABLE_VULKAN OFF CACHE BOOL "Experimental Adreno Vulkan path")

    # Guard against inheriting host build settings.
    # NOTE: this fork splits COMMON/SERVER out of TOOLS (unlike mainline).
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)      # no libcurl in NDK builds
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER ON CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)          # API-only server; skip WebUI
    set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)   # never fetch assets over network
    set(LLAMA_BUILD_HTML OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)     # NDK has no OpenMP

    if(BONSAI_ENABLE_VULKAN)
        # Host SDK supplies glslc + SPIRV/Vulkan header configs; the device
        # driver (Adreno) lives on the phone. find_package(Vulkan COMPONENTS
        # glslc) and vulkan-shaders-gen both resolve via VULKAN_SDK/PATH.
        set(BONSAI_VULKAN_SDK "" CACHE PATH "Host LunarG Vulkan SDK root")
        if(NOT BONSAI_VULKAN_SDK)
            message(FATAL_ERROR "BONSAI_ENABLE_VULKAN=ON needs -DBONSAI_VULKAN_SDK=<host sdk>")
        endif()
        set(ENV{VULKAN_SDK} "${BONSAI_VULKAN_SDK}")
        set(ENV{PATH} "${BONSAI_VULKAN_SDK}/bin:$ENV{PATH}")
        list(PREPEND CMAKE_PREFIX_PATH "${BONSAI_VULKAN_SDK}/share/cmake")
        # Android toolchain sets FIND_ROOT_PATH_MODE_PACKAGE=ONLY, which hides
        # host-SDK config packages from find_package(CONFIG); pin them and
        # widen the search roots explicitly.
        list(APPEND CMAKE_FIND_ROOT_PATH "${BONSAI_VULKAN_SDK}")
        set(SPIRV-Headers_DIR "${BONSAI_VULKAN_SDK}/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
        set(VulkanHeaders_DIR "${BONSAI_VULKAN_SDK}/share/cmake/VulkanHeaders" CACHE PATH "" FORCE)
        # Link the TARGET's loader stub (arm64, from NDK sysroot) — never the
        # host SDK's x86_64 libvulkan that FindVulkan would otherwise prefer.
        # API level pinned to 31 (= minSdk); CMAKE_SYSTEM_VERSION can read
        # as "1" during early toolchain phases and poisons the cached path.
        set(Vulkan_LIBRARY
            "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/31/libvulkan.so"
            CACHE FILEPATH "" FORCE)
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
        message(STATUS "Bonsai: Vulkan enabled (SDK: ${BONSAI_VULKAN_SDK})")
    endif()

    # armv8.2-a dotprod/i8mm are baseline on Cortex-X4/A7xx (8 Elite).
    add_compile_options(-march=armv8.2-a+dotprod+i8mm)

    add_subdirectory("${LLAMA_DIR}" llama-fork-build EXCLUDE_FROM_ALL)

    if(NOT TARGET llama-server)
        message(FATAL_ERROR "llama-server target missing — check the fork revision.")
    endif()

    # Stage the executable + the fork's runtime .so deps into the APK.
    # NOTE: no quotes around args — embedded quotes leak into argv under
    # ninja VERBATIM semantics (CMP0104-era behavior), breaking globs.
    if(NOT CMAKE_LIBRARY_OUTPUT_DIRECTORY)
        set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bonsai-jniLibs")
    endif()
    file(MAKE_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    add_custom_target(bonsai_package_server ALL
        COMMAND ${CMAKE_COMMAND}
            -DEXE=$<TARGET_FILE:llama-server>
            -DSRC=${CMAKE_BINARY_DIR}/bin
            -DDST=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
            -P ${CMAKE_CURRENT_SOURCE_DIR}/package_runtime.cmake
        DEPENDS llama-server
        COMMENT "Staging llama-server and runtime libs for APK"
        VERBATIM)
endif()

# ---------------------------------------------------------------------------
# GPU op correctness harness (Vulkan vs CPU reference), packaged as an
# executable .so so the app can spawn it from nativeLibraryDir.
# ---------------------------------------------------------------------------
if(BONSAI_ENABLE_VULKAN)
    add_executable(test-backend-ops "${LLAMA_DIR}/tests/test-backend-ops.cpp")
    target_include_directories(test-backend-ops PRIVATE
        "${LLAMA_DIR}/include"
        "${LLAMA_DIR}/ggml/include")
    target_link_libraries(test-backend-ops PRIVATE ggml)
    if(NOT CMAKE_LIBRARY_OUTPUT_DIRECTORY)
        set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bonsai-jniLibs")
    endif()
    set_target_properties(test-backend-ops PROPERTIES
        OUTPUT_NAME "libtestops"
        SUFFIX ".so"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()

# ---------------------------------------------------------------------------
# Experimental in-process JNI engine seam.
# Off by default: the subprocess server is the shipping path (drafter support,
# robustness). This seam exists so a future MTP-head patch in the fork can be
# driven without a process boundary.
# ---------------------------------------------------------------------------
if(BONSAI_WITH_LLAMA_JNI)
    target_sources(bonsai_native PRIVATE bonsai_jni_engine.cpp)
    target_include_directories(bonsai_native PRIVATE
        "${LLAMA_DIR}/include" "${LLAMA_DIR}/ggml/include")
    target_link_libraries(bonsai_native PRIVATE common llama ggml)
    target_compile_definitions(bonsai_native PRIVATE BONSAI_WITH_LLAMA_JNI=1)
endif()
