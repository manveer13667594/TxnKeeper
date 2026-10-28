cmake_minimum_required(VERSION 3.22.1)
project(whisperkit LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON) # static whisper/ggml get linked into our shared lib

# ggml is unusably slow at -O0, so keep Debug builds optimised too.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

# --- whisper.cpp (pinned release, fetched at first build) -------------------
# Offline / vendored? Pass -DWHISPER_CPP_DIR=/path/to/whisper.cpp
include(FetchContent)
if (WHISPER_CPP_DIR)
    set(FETCHCONTENT_SOURCE_DIR_WHISPER_CPP ${WHISPER_CPP_DIR})
endif()
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.9.5
)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)  # link everything into one .so
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a" AND WHISPERKIT_ARM_FAST)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(whisper_cpp)

# --- JNI bridge -------------------------------------------------------------
add_library(whisperkit SHARED whisperkit_jni.cpp)
target_link_libraries(whisperkit PRIVATE whisper)

if (ANDROID)
    target_link_libraries(whisperkit PRIVATE log)
    # Required for Android 15+ devices with 16 KB pages.
    target_link_options(whisperkit PRIVATE -Wl,-z,max-page-size=16384)
endif()
