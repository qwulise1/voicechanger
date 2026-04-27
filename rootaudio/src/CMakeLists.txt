cmake_minimum_required(VERSION 3.22.1)
project(qwulivoice_root_audio LANGUAGES CXX)

add_library(qwv_preprocess SHARED qwv_preprocess.cpp)

target_compile_features(qwv_preprocess PRIVATE cxx_std_20)
target_compile_options(qwv_preprocess PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(qwv_preprocess PRIVATE log)
