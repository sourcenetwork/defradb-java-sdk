#!/bin/bash
# Builds the SDK against a defradb.rs checkout: the Rust C API library
# (crates/entry-points/ffi) with cargo, its header defra.h with cbindgen,
# the JNI wrapper against both, and the JAR or AAR that packages them.
set -euo pipefail

android_present=false
linux_present=false
macos_present=false
cleanup=false
defra_dir=""
desktop_features=""
android_features="native,iroh"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    echo "Usage: ./build.sh --defra-dir <defradb.rs> [ --linux ] [ --macos ] [ --android ] [ --cleanup ]"
    echo "                  [ --features <list> ] [ --android-features <list> ]"
    echo "Select --linux, --macos, or --android. Android can be combined with the host desktop target."
    echo "--features replaces the ffi crate's default features for the desktop library."
    echo "--android-features sets the Android library's features (default: $android_features)."
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --android) android_present=true; shift ;;
        --linux) linux_present=true; shift ;;
        --macos) macos_present=true; shift ;;
        --cleanup) cleanup=true; shift ;;
        --defra-dir|--features|--android-features)
            if [[ $# -lt 2 || -z "$2" || "$2" = --* ]]; then
                echo "Error: $1 requires a value"
                exit 1
            fi
            case "$1" in
                --defra-dir) defra_dir="$2" ;;
                --features) desktop_features="$2" ;;
                --android-features) android_features="$2" ;;
            esac
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown argument: $1"; usage; exit 1 ;;
    esac
done

if [ -z "$defra_dir" ]; then
    echo "Error: --defra-dir is required"
    usage
    exit 1
fi
if [ "$android_present" = false ] && [ "$linux_present" = false ] && [ "$macos_present" = false ]; then
    echo "Error: select --linux, --macos, or --android"
    exit 1
fi

defra_dir_abs="$(cd "$defra_dir" && pwd)"
if [ ! -f "$defra_dir_abs/crates/entry-points/ffi/cbindgen.toml" ]; then
    echo "Error: $defra_dir_abs is not a defradb.rs checkout (no crates/entry-points/ffi/cbindgen.toml)"
    exit 1
fi
target_dir="${CARGO_TARGET_DIR:-$defra_dir_abs/target}"

if [ "$linux_present" = true ] && [ "$(uname -s)" != Linux ]; then
    echo "Error: --linux requires a Linux host"
    exit 1
fi
if [ "$macos_present" = true ] && [ "$(uname -s)" != Darwin ]; then
    echo "Error: --macos requires a macOS host"
    exit 1
fi

if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = Darwin ]; then
    JAVA_HOME="$(/usr/libexec/java_home)"
    export JAVA_HOME
fi
if [ ! -f "${JAVA_HOME:-}/include/jni.h" ]; then
    echo "Error: JAVA_HOME must point to a full JDK with include/jni.h"
    exit 1
fi
for tool in cargo cbindgen; do
    if ! command -v "$tool" >/dev/null; then
        echo "Error: $tool not found on PATH"
        exit 1
    fi
done

c_dir="$script_dir/src/main/c"

echo "Generating defra.h..."
cbindgen --quiet \
    --config "$defra_dir_abs/crates/entry-points/ffi/cbindgen.toml" \
    --crate ffi \
    --output "$c_dir/defra.h" \
    "$defra_dir_abs"

# The ffi crate builds libffi; the SDK ships it as libdefradb, the name the
# JNI wrapper links and NativeLoader extracts. Only the cdylib is built, and
# on ELF targets it is linked with that name as its DT_SONAME, which the
# Android loader expects. The flag reaches the ffi crate alone, so no
# dependency rebuilds for it.
ELF_SONAME=(-C link-arg=-Wl,-soname,libdefradb.so)

# Trial of nuclei 0.5.9, which stops calling the raw epoll_wait syscall on
# Android x86_64 (Android's app seccomp filter kills an app that makes it).
# Until defradb.rs pins it, the build fetches that release from crates.io and
# points cargo at it with a [patch]; defradb.rs's ^0.5.8 requirement accepts
# it. Once defradb.rs's Cargo.lock names this version or a later one,
# nothing is overridden.
nuclei_version=0.5.9
nuclei_args=()
nuclei_override=false
pinned_nuclei="$(awk '/^name = "nuclei"$/ { getline; gsub(/version = |"/, ""); print }' "$defra_dir_abs/Cargo.lock")"
# True when version $1 is older than version $2 (dotted numbers).
version_older() {
    awk -v a="$1" -v b="$2" 'BEGIN {
        n = split(a, x, "."); m = split(b, y, ".")
        for (i = 1; i <= (n > m ? n : m); i++) {
            if (x[i] + 0 < y[i] + 0) exit 0
            if (x[i] + 0 > y[i] + 0) exit 1
        }
        exit 1
    }'
}
if [ "$(printf '%s\n' "$pinned_nuclei" | grep -c .)" != 1 ]; then
    echo "Error: expected one nuclei version in $defra_dir_abs/Cargo.lock, found: ${pinned_nuclei:-none}"
    exit 1
fi
if ! version_older "$pinned_nuclei" "$nuclei_version"; then
    echo "defradb.rs pins nuclei $pinned_nuclei, at least $nuclei_version; remove the nuclei override from build.sh."
else
    fetch_dir="$script_dir/build/nuclei-fetch"
    mkdir -p "$fetch_dir/src"
    printf '[package]\nname = "nuclei-fetch"\nversion = "0.0.0"\nedition = "2021"\n\n[dependencies]\nnuclei = "=%s"\n\n[workspace]\n' \
        "$nuclei_version" > "$fetch_dir/Cargo.toml"
    : > "$fetch_dir/src/lib.rs"
    nuclei_src="$(cd "$fetch_dir" && cargo metadata --format-version 1 2>/dev/null \
        | grep -o "\"manifest_path\":\"[^\"]*/nuclei-$nuclei_version/Cargo.toml\"" | head -n 1 \
        | sed -e 's/^"manifest_path":"//' -e 's#/Cargo.toml"$##' || true)"
    if [ ! -f "$nuclei_src/Cargo.toml" ]; then
        echo "Error: could not fetch nuclei $nuclei_version from crates.io"
        exit 1
    fi
    echo "Building against nuclei $nuclei_version (defradb.rs pins ${pinned_nuclei:-none})."
    nuclei_args=(--config "patch.crates-io.nuclei.path=\"$nuclei_src\"")
    nuclei_override=true
fi

# Runs cargo in the checkout: [VAR=value ...] cargo <args>. With the nuclei
# override, cargo rewrites Cargo.lock; defradb.rs's copy is put back as it
# was, and the build fails if cargo did not use the override.
cargo_defra() {
    local envs=()
    while [ "$1" != cargo ]; do envs+=("$1"); shift; done
    shift
    local lock="$defra_dir_abs/Cargo.lock"
    local status=0
    cp -p "$lock" "$lock.sdk-backup"
    (cd "$defra_dir_abs" && env ${envs[@]+"${envs[@]}"} cargo ${nuclei_args[@]+"${nuclei_args[@]}"} "$@") || status=$?
    if [ "$status" = 0 ] && [ "$nuclei_override" = true ] \
        && { grep -q '^\[\[patch.unused\]\]' "$lock" \
            || ! grep -A1 '^name = "nuclei"$' "$lock" | grep -q "^version = \"$nuclei_version\"$"; }; then
        echo "Error: cargo did not build against nuclei $nuclei_version"
        status=1
    fi
    mv -f "$lock.sdk-backup" "$lock"
    return "$status"
}

build_desktop() {
    local feature_args=()
    if [ -n "$desktop_features" ]; then
        feature_args=(--no-default-features --features "$desktop_features")
    fi
    local link_args=()
    if [ "$(uname -s)" = Linux ]; then
        link_args=("${ELF_SONAME[@]}")
    fi
    cargo_defra cargo rustc --release -p ffi --lib --crate-type cdylib ${feature_args[@]+"${feature_args[@]}"} -- ${link_args[@]+"${link_args[@]}"}
}

# -------------------------
# ANDROID BUILD
# -------------------------
if [ "$android_present" = true ]; then
    ndk="${ANDROID_NDK:?Set ANDROID_NDK for Android builds}"
    case "$(uname -s)" in
        Darwin) host_tag=darwin-x86_64 ;;
        Linux) host_tag=linux-x86_64 ;;
        *) echo "Error: unsupported host for Android builds"; exit 1 ;;
    esac
    toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"

    for abi in arm64-v8a x86_64; do
        case "$abi" in
            arm64-v8a) triple=aarch64-linux-android ;;
            x86_64) triple=x86_64-linux-android ;;
        esac
        env_triple="$(echo "$triple" | tr '[:lower:]-' '[:upper:]_')"
        cc_triple="${triple//-/_}"
        echo "Building libdefradb for Android $abi..."
        # Run in the checkout so the target goes to the toolchain its
        # rust-toolchain.toml pins, the one cargo builds with there.
        (cd "$defra_dir_abs" && rustup target add "$triple" >/dev/null)
        cargo_defra \
            "CARGO_TARGET_${env_triple}_LINKER=$toolchain/${triple}24-clang" \
            "CC_${cc_triple}=$toolchain/${triple}24-clang" \
            "CXX_${cc_triple}=$toolchain/${triple}24-clang++" \
            "AR_${cc_triple}=$toolchain/llvm-ar" \
            cargo rustc --release -p ffi --lib --crate-type cdylib --target "$triple" \
            --no-default-features --features "$android_features" \
            -- "${ELF_SONAME[@]}"
        mkdir -p "$script_dir/src/main/jniLibs/$abi"
        cp "$target_dir/$triple/release/libffi.so" "$script_dir/src/main/jniLibs/$abi/libdefradb.so"
    done

    echo "Building the JNI wrapper for Android..."
    bash "$c_dir/build.sh" --android

    echo "Packaging the Android AAR..."
    "$script_dir/gradlew" -p "$script_dir" -b "$script_dir/build-android.gradle" assembleRelease
fi

# -------------------------
# LINUX BUILD
# -------------------------
if [ "$linux_present" = true ]; then
    echo "Building libdefradb for Linux..."
    build_desktop
    mkdir -p "$script_dir/src/main/linuxLibs"
    cp "$target_dir/release/libffi.so" "$script_dir/src/main/linuxLibs/libdefradb.so"

    echo "Building the JNI wrapper for Linux..."
    bash "$c_dir/build.sh" --linux

    echo "Packaging the Linux JAR..."
    "$script_dir/gradlew" -p "$script_dir" -b "$script_dir/build-linux.gradle" build
fi

# -------------------------
# MACOS BUILD
# -------------------------
if [ "$macos_present" = true ]; then
    echo "Building libdefradb for macOS..."
    build_desktop
    mkdir -p "$script_dir/src/main/macosLibs"
    cp "$target_dir/release/libffi.dylib" "$script_dir/src/main/macosLibs/libdefradb.dylib"
    # The wrapper finds the library beside itself through @rpath, not at the
    # path cargo built it in.
    install_name_tool -id @rpath/libdefradb.dylib "$script_dir/src/main/macosLibs/libdefradb.dylib"

    echo "Building the JNI wrapper for macOS..."
    bash "$c_dir/build.sh" --macos

    echo "Packaging the macOS JAR..."
    "$script_dir/gradlew" -p "$script_dir" -b "$script_dir/build-macos.gradle" build
fi

# -------------------------
# CLEANUP
# -------------------------
if [ "$cleanup" = true ]; then
    echo "Cleaning up build artifacts..."
    rm -f "$c_dir/defra.h"
    rm -rf "$script_dir/build/nuclei-fetch"
    rm -f "$script_dir/src/main/linuxLibs/libdefradb.so" "$script_dir/src/main/linuxLibs/libnativewrapper.so"
    rm -f "$script_dir/src/main/macosLibs/libdefradb.dylib" "$script_dir/src/main/macosLibs/libnativewrapper.dylib"
    for abi in arm64-v8a x86_64; do
        rm -f "$script_dir/src/main/jniLibs/$abi/libdefradb.so" "$script_dir/src/main/jniLibs/$abi/libnativewrapper.so"
    done
fi
