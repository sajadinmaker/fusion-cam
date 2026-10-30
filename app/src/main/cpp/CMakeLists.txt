cmake_minimum_required(VERSION 3.22.1)
project("fusion" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-O2)

add_library(fusion SHARED FusionNative.cpp)

find_library(log-lib log)
target_link_libraries(fusion ${log-lib})
