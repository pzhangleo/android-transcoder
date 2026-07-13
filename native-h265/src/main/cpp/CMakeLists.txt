cmake_minimum_required(VERSION 3.22.1)

project(native_h265)

include(FetchContent)

set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_CLI OFF CACHE BOOL "" FORCE)
set(ENABLE_ASSEMBLY OFF CACHE BOOL "" FORCE)
set(ENABLE_HDR10_PLUS OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBNUMA OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        x265
        GIT_REPOSITORY https://github.com/videolan/x265.git
        GIT_TAG 07295ba7ab551bb9c1580fdaee3200f1b45711b7
        GIT_SHALLOW TRUE
        SOURCE_SUBDIR source
)
FetchContent_MakeAvailable(x265)

add_library(native_h265 SHARED native_h265_jni.cpp)

target_include_directories(native_h265 PRIVATE
        ${x265_SOURCE_DIR}/source
        ${x265_BINARY_DIR}
)

target_compile_definitions(native_h265 PRIVATE NATIVE_H265_HAS_X265=1)
add_dependencies(native_h265 x265-static)

find_library(log-lib log)
target_link_libraries(native_h265 x265-static ${log-lib})
