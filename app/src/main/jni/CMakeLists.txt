cmake_minimum_required(VERSION 3.22.1)
project(diskusage C)

# Android 10+ does not allow running executables from app data directories
# like `/data/data` or `/data/user/0`. As a workaround the scanner is named
# `libscan.so`, so that it gets packed into the apk and installed into the
# native library directory, from where the app can run it.
#
# It must be built as a real (PIE) executable: a shared library has no
# program interpreter and entry point, so it cannot be run.
add_executable(scan scan.c)
set_target_properties(scan PROPERTIES
        OUTPUT_NAME "libscan.so"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
