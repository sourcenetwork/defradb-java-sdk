#!/bin/bash
# Compiles the JNI wrapper, nativewrapper.c, against defra.h and links it to
# the libdefradb the top-level build.sh staged for each target.
set -euo pipefail

android_present=false
linux_present=false
macos_present=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --android) android_present=true; shift ;;
        --linux) linux_present=true; shift ;;
        --macos) macos_present=true; shift ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done

cd "$(dirname "${BASH_SOURCE[0]}")"
JNI=../jniLibs
CFLAGS=(-std=c11 -fPIC -O2 -pthread -Wall -Wextra -Werror)

if [ ! -f defra.h ]; then
    echo "Error: defra.h not found; run the top-level build.sh first"
    exit 1
fi

case "$(uname -s)" in
    Darwin)
        HOST_TAG=darwin-x86_64
        JNI_PLATFORM=darwin
        ;;
    Linux)
        HOST_TAG=linux-x86_64
        JNI_PLATFORM=linux
        ;;
    *) echo "Unsupported host"; exit 1 ;;
esac
JNI_INCLUDES=(-I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/$JNI_PLATFORM")

if [ "$linux_present" = true ] && [ "$JNI_PLATFORM" != linux ]; then
    echo "Error: --linux requires a Linux host"
    exit 1
fi
if [ "$macos_present" = true ] && [ "$JNI_PLATFORM" != darwin ]; then
    echo "Error: --macos requires a macOS host"
    exit 1
fi

if [ "$android_present" = true ]; then
    NDK=${ANDROID_NDK:?Set ANDROID_NDK for Android builds}
    for abi in arm64-v8a x86_64; do
        case "$abi" in
            arm64-v8a) triple=aarch64-linux-android ;;
            x86_64) triple=x86_64-linux-android ;;
        esac
        mkdir -p "$JNI/$abi"
        # No JDK includes: the NDK sysroot has Android's own jni.h, whose
        # declarations differ from the JDK's (AttachCurrentThread takes a
        # JNIEnv**, not a void**).
        "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/${triple}24-clang" \
            "${CFLAGS[@]}" \
            -shared -o "$JNI/$abi/libnativewrapper.so" \
            nativewrapper.c \
            -L"$JNI/$abi" -ldefradb
    done
fi

if [ "$linux_present" = true ]; then
    LINUX_OUT=../linuxLibs
    mkdir -p "$LINUX_OUT"
    gcc "${CFLAGS[@]}" "${JNI_INCLUDES[@]}" \
        -Wl,-rpath,'$ORIGIN' \
        -shared -o "$LINUX_OUT/libnativewrapper.so" \
        nativewrapper.c \
        -L"$LINUX_OUT" -ldefradb
fi

if [ "$macos_present" = true ]; then
    MACOS_OUT=../macosLibs
    mkdir -p "$MACOS_OUT"
    clang "${CFLAGS[@]}" "${JNI_INCLUDES[@]}" \
        -Wl,-rpath,@loader_path \
        -Wl,-install_name,@rpath/libnativewrapper.dylib \
        -Wl,-U,_defra_profiling_start -Wl,-U,_defra_profiling_stop \
        -dynamiclib -o "$MACOS_OUT/libnativewrapper.dylib" \
        nativewrapper.c \
        -L"$MACOS_OUT" -ldefradb
fi
