cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

# --------------------------------------------------------------------------
# v4.8 Multimodal: libmtmd (tools/mtmd/ + alt klasorler)
# --------------------------------------------------------------------------
set(MTMD_DIR ${LLAMA_SRC}/tools/mtmd)

file(GLOB_RECURSE MTMD_SOURCES "${MTMD_DIR}/*.cpp")
list(REMOVE_ITEM MTMD_SOURCES "${MTMD_DIR}/mtmd-cli.cpp")
list(REMOVE_ITEM MTMD_SOURCES "${MTMD_DIR}/deprecation-warning.cpp")
list(REMOVE_ITEM MTMD_SOURCES "${MTMD_DIR}/debug/mtmd-debug.cpp")

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp
        ${MTMD_SOURCES})

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

# vendor/nlohmann/ icinde json_fwd.hpp dogrudan bulunuyor.
# #include <nlohmann/json_fwd.hpp> icin vendor/ dizinini include path'e ekliyoruz.
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${MTMD_DIR}
        ${LLAMA_SRC}/vendor)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log)
