cmake_minimum_required(VERSION 3.22.1)
project(threadsbf C)

# The Brainfuck policy core: AOT-generated programs plus the runtime and JNI
# glue. Regenerate generated/ with tools/bftool/gen.py; never edit it.
add_library(threadsbf SHARED
        bf_engine.c
        bf_jni.c
        generated/bf_programs.generated.c)

target_compile_options(threadsbf PRIVATE
        -O2
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -Wall)

if (CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
    target_compile_options(threadsbf PRIVATE -flto)
    target_link_options(threadsbf PRIVATE -flto -Wl,--gc-sections -Wl,--icf=all)
endif ()
