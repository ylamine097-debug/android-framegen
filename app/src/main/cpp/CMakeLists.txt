cmake_minimum_required(VERSION 3.22.1)
project(framegen_ai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(RIFE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rife-ncnn-vulkan)
set(RIFE_SRC  ${RIFE_ROOT}/src)

if(NOT EXISTS "${RIFE_SRC}/rife.cpp")
    message(FATAL_ERROR "RIFE sources are missing. The CI workflow must fetch rife-ncnn-vulkan recursively before Gradle configuration.")
endif()

# Build ncnn with Vulkan and its bundled glslang so RIFE can compile its
# compute shaders at runtime on Android.
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_VULKAN_ONLINE_SPIRV ON CACHE BOOL "" FORCE)
set(NCNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(NCNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(NCNN_OPENMP OFF CACHE BOOL "" FORCE)
set(NCNN_INT8 OFF CACHE BOOL "" FORCE)

add_subdirectory(${RIFE_ROOT}/src/ncnn ncnn-build EXCLUDE_FROM_ALL)

set(GENERATED_DIR ${CMAKE_CURRENT_BINARY_DIR}/rife-generated)
file(MAKE_DIRECTORY ${GENERATED_DIR})

set(RIFE_SHADERS
    rife_preproc.comp
    rife_postproc.comp
    rife_preproc_tta.comp
    rife_postproc_tta.comp
    rife_flow_tta_avg.comp
    rife_v2_flow_tta_avg.comp
    rife_v4_flow_tta_avg.comp
    rife_flow_tta_temporal_avg.comp
    rife_v2_flow_tta_temporal_avg.comp
    rife_v4_flow_tta_temporal_avg.comp
    rife_out_tta_temporal_avg.comp
    rife_v4_timestep.comp
    rife_v4_timestep_tta.comp
    warp.comp
    warp_pack4.comp
    warp_pack8.comp
)

set(RIFE_SHADER_HEADERS)
foreach(SHADER IN LISTS RIFE_SHADERS)
    get_filename_component(SHADER_NAME ${SHADER} NAME_WE)
    set(OUT ${GENERATED_DIR}/${SHADER_NAME}.comp.hex.h)

    add_custom_command(
        OUTPUT ${OUT}
        COMMAND ${CMAKE_COMMAND}
            -DSHADER_SRC=${RIFE_SRC}/${SHADER}
            -DSHADER_COMP_HEADER=${OUT}
            -P ${RIFE_SRC}/generate_shader_comp_header.cmake
        DEPENDS ${RIFE_SRC}/${SHADER}
                ${RIFE_SRC}/generate_shader_comp_header.cmake
        VERBATIM
    )
    list(APPEND RIFE_SHADER_HEADERS ${OUT})
endforeach()

add_custom_target(rife_shader_headers DEPENDS ${RIFE_SHADER_HEADERS})

add_library(framegen_ai SHARED
    framegen_ai.cpp
    ${RIFE_SRC}/rife.cpp
    ${RIFE_SRC}/warp.cpp
    ${RIFE_SHADER_HEADERS}
)

add_dependencies(framegen_ai rife_shader_headers)

target_include_directories(framegen_ai PRIVATE
    ${RIFE_SRC}
    ${GENERATED_DIR}
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(framegen_ai
    ncnn
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
)
