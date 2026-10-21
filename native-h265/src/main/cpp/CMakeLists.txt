cmake_minimum_required(VERSION 3.22.1)

project(native_h265)

include(FetchContent)

set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_CLI OFF CACHE BOOL "" FORCE)
set(ENABLE_HDR10_PLUS OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBNUMA OFF CACHE BOOL "" FORCE)
set(ENABLE_PIC ON CACHE BOOL "" FORCE)

# x265 spends most of its time in hand written NEON assembly, but this pinned revision cannot be
# assembled with the Android NDK toolchain:
#   * its aarch64 rule runs `${CMAKE_CXX_COMPILER} -fPIC -c x.S` WITHOUT the NDK target triple, so
#     clang assembles AArch64 sources for the host CPU and fails on `.section .rodata`;
#   * even with `--target=aarch64-none-linux-android21` the sources use `.func`/`.endfunc`, which
#     LLVM's integrated assembler (the only assembler left in NDK 27) rejects.
# So the pure C path is used. Enabling it needs a newer x265 or a patched build rule; measure before
# assuming the win, because the C path already has NEON intrinsics for the hot loops.
set(ENABLE_ASSEMBLY OFF CACHE BOOL "" FORCE)

# x265 is one big hot loop. AGP maps the debug variant to CMAKE_BUILD_TYPE=Debug, i.e. -O0, which
# makes debug builds roughly an order of magnitude slower than release ones. Keep -g but add -O3.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG" CACHE STRING "" FORCE)
endif()

# Fetch the exact upstream commit as a hash-verified tarball instead of `git clone`. This keeps the
# build reproducible and works on machines or CI images without git or behind a strict proxy.
# Corporate networks often cannot reach github.com at all, so a pre-downloaded archive placed in
# native-h265/third_party/ takes precedence (see native-h265/README.md).
set(X265_URL https://github.com/videolan/x265/archive/07295ba7ab551bb9c1580fdaee3200f1b45711b7.tar.gz)
set(X265_LOCAL_ARCHIVE "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/x265-07295ba.tar.gz")
if(EXISTS "${X265_LOCAL_ARCHIVE}")
    set(X265_URL "${X265_LOCAL_ARCHIVE}")
    message(STATUS "Using local x265 archive: ${X265_LOCAL_ARCHIVE}")
endif()

FetchContent_Declare(
        x265
        URL ${X265_URL}
        URL_HASH SHA256=1c72293fad0517f2eb24e19c3bc9edd988a7304bac897e372915a6052d7d253e
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
