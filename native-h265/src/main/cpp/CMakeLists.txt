cmake_minimum_required(VERSION 3.22.1)

project(native_h265)

add_library(native_h265 SHARED native_h265_jni.cpp)

find_library(log-lib log)
target_link_libraries(native_h265 ${log-lib})
