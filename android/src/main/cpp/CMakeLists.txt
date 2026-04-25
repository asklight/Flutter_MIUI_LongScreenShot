cmake_minimum_required(VERSION 3.10.2)

project(miui_long_screenshot)

if (NOT DEFINED DART_SDK_INCLUDE)
    message(FATAL_ERROR "DART_SDK_INCLUDE is required")
endif()

add_library(
        miui_long_screenshot
        SHARED
        ${DART_SDK_INCLUDE}/dart_api_dl.c
        miui_long_screenshot.cpp
)

target_include_directories(
        miui_long_screenshot
        PRIVATE
        ${DART_SDK_INCLUDE}
)

find_library(log-lib log)

target_link_libraries(
        miui_long_screenshot
        ${log-lib}
)
