#!/bin/bash
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
    mkdir -p $JNI/arm64-v8a
    "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android24-clang" \
        -fPIC \
        -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/$JNI_PLATFORM" \
        -L$JNI/arm64-v8a -ldefradb \
        -shared -o $JNI/arm64-v8a/libnativewrapper.so \
        nativewrapper.c

    mkdir -p $JNI/x86_64
    "$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/x86_64-linux-android24-clang" \
        -fPIC \
        -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/$JNI_PLATFORM" \
        -L$JNI/x86_64 -ldefradb \
        -shared -o $JNI/x86_64/libnativewrapper.so \
        nativewrapper.c
fi

if [ "$linux_present" = true ]; then
    LINUX_OUT=../linuxLibs
    mkdir -p $LINUX_OUT
    gcc \
        -fPIC \
        -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/$JNI_PLATFORM" \
        -Wl,-rpath,'$ORIGIN' \
        -shared \
        -o $LINUX_OUT/libnativewrapper.so \
        nativewrapper.c \
        -L$LINUX_OUT \
        -ldefradb
fi

if [ "$macos_present" = true ]; then
    MACOS_OUT=../macosLibs
    mkdir -p $MACOS_OUT
    clang \
        -fPIC \
        -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/darwin" \
        -Wl,-rpath,@loader_path \
        -Wl,-install_name,@rpath/libnativewrapper.dylib \
        -dynamiclib \
        -o $MACOS_OUT/libnativewrapper.dylib \
        nativewrapper.c \
        -L$MACOS_OUT \
        -ldefradb
fi
