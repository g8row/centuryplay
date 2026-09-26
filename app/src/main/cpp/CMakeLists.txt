cmake_minimum_required(VERSION 3.22.1)
project(centuryplay_native C CXX)

# Apple's reference ALAC codec (Apache 2.0), see alac/LICENSE-Apache-2.0.txt.
add_library(alac_codec STATIC
        alac/ALACEncoder.cpp alac/ALACDecoder.cpp alac/ALACBitUtilities.c alac/EndianPortable.c
        alac/ag_enc.c alac/ag_dec.c alac/dp_enc.c alac/dp_dec.c alac/matrix_enc.c alac/matrix_dec.c)
target_include_directories(alac_codec PUBLIC alac)
target_compile_options(alac_codec PRIVATE -O3 -w)

add_library(centuryplay_native SHARED alac_jni.cpp)
target_compile_options(centuryplay_native PRIVATE -O3 -Wall)
target_link_libraries(centuryplay_native alac_codec log)
