cmake_minimum_required(VERSION 3.22.1)
project("kortex-llama")

# Llama.cpp options
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)

add_subdirectory(llama.cpp)

add_library(kortex-llama SHARED llama_bridge.cpp)

target_include_directories(kortex-llama PRIVATE
        llama.cpp
        llama.cpp/common
        llama.cpp/include)

target_link_libraries(kortex-llama
        llama
        ggml
        log
        android)
