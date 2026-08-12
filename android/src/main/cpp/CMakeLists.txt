cmake_minimum_required(VERSION 3.10.2)

project(miui_long_screenshot)

if (NOT DEFINED DART_SDK_INCLUDE)
    message(FATAL_ERROR "DART_SDK_INCLUDE is required")
endif()

# Windows 下 DART_SDK_INCLUDE 是反斜杠路径（可能含空格，如 "D:\Program Files\..."），
# CMake 会把 \P、\U 等当作转义序列导致 "Invalid character escape" 报错。
# 统一转为正斜杠 CMAKE 路径后再使用。
file(TO_CMAKE_PATH "${DART_SDK_INCLUDE}" DART_SDK_INCLUDE_CMAKE)

# 直接把 SDK 里的 dart_api_dl.c 作为源文件时，其 obj 输出路径会镜像
# "D_/Program_Files/..." 绝对路径，加上 Android 构建产物目录后可能超过
# Windows MAX_PATH 导致 mkdir 失败。改为配置期复制到 build 目录内编译，
# 既避免长路径问题，也无需在仓库内维护 SDK 文件副本。
file(COPY "${DART_SDK_INCLUDE_CMAKE}/dart_api_dl.c"
     DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")

add_library(
        miui_long_screenshot
        SHARED
        "${CMAKE_CURRENT_BINARY_DIR}/dart_api_dl.c"
        miui_long_screenshot.cpp
)

target_include_directories(
        miui_long_screenshot
        PRIVATE
        ${DART_SDK_INCLUDE_CMAKE}
)

find_library(log-lib log)

target_link_libraries(
        miui_long_screenshot
        ${log-lib}
)
