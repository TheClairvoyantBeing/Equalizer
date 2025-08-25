cmake_minimum_required(VERSION 3.22.1)
project("hearwell")

add_library(
        native-lib
        SHARED
        native-lib.cpp
        kiss_fft.c
        kiss_fftr.c
)

find_library(
        log-lib
        log
)

target_link_libraries(
        native-lib
        ${log-lib}
        oboe
)
add_subdirectory(oboe)