cmake_minimum_required(VERSION 3.22.1)

project("fractaldreams-native")

add_library(
    fractaldreams-native
    SHARED
    native-lib.cpp)

find_library(log-lib log)

target_link_libraries(
    fractaldreams-native
    ${log-lib})

set_target_properties(fractaldreams-native PROPERTIES
    CXX_STANDARD 17)

set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")
