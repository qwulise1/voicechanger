cmake_minimum_required(VERSION 3.22.1)

project(voicechanger_native)

add_library(
    voicechanger-native
    SHARED
    voicechanger_native.cpp
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(
    voicechanger-native
    ${log-lib}
    ${aaudio-lib}
)
