# Development

This guide covers building and testing the SDK itself. For installation and application usage, start with the [README](README.md).

## Prerequisites

All builds require a local [defradb.rs](https://github.com/sourcenetwork/defradb.rs) checkout and its build prerequisites. The SDK and CI target the latest defradb.rs default branch. Keep your local checkout up to date; upstream C API changes should be reflected in the Java/JNI bindings rather than worked around by pinning an older revision. The Gradle wrapper downloads Gradle 8.3 when necessary.

Every build needs [rustup](https://rustup.rs), which installs the Rust toolchain pinned in the checkout's `rust-toolchain.toml` the first time Cargo runs there, and `cbindgen` (`cargo install cbindgen`). defradb.rs also needs `protoc` (`brew install protobuf`, or `protobuf-compiler` on Debian and Ubuntu); on Linux it also needs `libssl-dev`, `pkg-config` and `libdbus-1-dev`.

Desktop builds require a full JDK (11–20, preferably 17) with `JAVA_HOME` set and a C compiler: Xcode Command Line Tools on macOS, or GCC on Linux. Gradle 8.3 cannot run on JDK 21 or later.

Android builds require JDK 17, Android SDK API 34, and an Android NDK with `ANDROID_NDK` set. The build selects the Darwin or Linux NDK host toolchain automatically and adds the Rust Android targets with `rustup`.

## Automated build

```shell
# Linux
./build.sh --defra-dir /path/to/defradb.rs --linux

# macOS
./build.sh --defra-dir /path/to/defradb.rs --macos

# Android; can also be combined with the host desktop flag
./build.sh --defra-dir /path/to/defradb.rs --android
```

The script:

1. Generates `src/main/c/defra.h` from the checkout's `crates/entry-points/ffi` crate with `cbindgen`, using that crate's `cbindgen.toml`.
2. Builds the crate's shared library with `cargo rustc --release -p ffi --lib --crate-type cdylib`, for the host or for `aarch64-linux-android` and `x86_64-linux-android` with the NDK's clang as linker. Cargo names the library `libffi`; the script stages it as `libdefradb`, the name the JNI wrapper links and `NativeLoader` extracts, and on Linux and Android links it with that name as its `DT_SONAME`. `CARGO_TARGET_DIR` is respected.
3. Compiles the JNI wrapper and packages the Java classes and native libraries. Desktop output is `build/libs/defradb.jar`, for the host architecture only. Android output is `build/outputs/aar/defradb-release.aar`, containing `arm64-v8a` and `x86_64` libraries.

Android builds patch one line of nuclei, defradb.rs's network runtime, until a nuclei release fixes it. On x86_64, nuclei waits for network events with the raw `epoll_wait` system call. Android's seccomp filter for apps blocks that call, and the kernel kills the app as soon as a node starts. The script copies the nuclei version named in the checkout's `Cargo.lock` from the cargo registry to `build/nuclei-patch`, switches that call to `epoll_pwait` on Android, and builds with `cargo --config patch.crates-io.nuclei.path=…`. It restores `Cargo.lock` afterwards, so the checkout is left unchanged. The build fails if the code it patches has changed or cargo does not use the copy. If nuclei already has the fix, the script skips the patch and says the workaround can be removed. Desktop builds use nuclei unchanged.

Options:

- `--features <list>` replaces the `ffi` crate's default features for the desktop library, for example `--features native,wasmtime-runtime,vera,libp2p,profiling` to add profiling to the defaults.
- `--android-features <list>` sets the Android library's features (default `native,iroh`).
- `--cleanup` removes the generated header and native libraries after successful packaging.
- `--help` displays usage.

The script can be invoked from any directory. Relative `--defra-dir` paths resolve from the caller's working directory.

A defradb.rs build stamps the build time from its git state. Running Cargo there from two different git installations (for example Windows Git and WSL's git on the same checkout) rewrites the git index each time and forces a full rebuild; use one.

## Native compilation and packaging

Native libraries are staged in `src/main/macosLibs`, `src/main/linuxLibs`, or `src/main/jniLibs/<abi>`. The generated header is staged in `src/main/c`.

To rebuild just the desktop JNI wrapper and JAR using the existing header and library:

```shell
# Linux
./src/main/c/build.sh --linux
./gradlew -b build-linux.gradle build

# macOS
./src/main/c/build.sh --macos
./gradlew -b build-macos.gradle build
```

macOS uses `.dylib` libraries with `@loader_path`; Linux uses `.so` libraries with `$ORIGIN`. The Java loader selects the platform's library extension automatically.

`nativewrapper.c` is tracked in this repository and compiled with `-Wall -Wextra -Werror`. It registers its natives with `RegisterNatives` in `JNI_OnLoad`, so a Java method whose declaration no longer matches the wrapper fails when the library loads, not at its first call. `defra_profiling_start` and `defra_profiling_stop` exist only in libraries built with the `profiling` feature; the wrapper references them weakly, and the Java calls report that profiling is unavailable when they are missing.

## Tests

CI builds the desktop library on macOS and Linux and runs both desktop tests, then builds the AAR and runs the Android instrumented test.

```shell
mkdir -p test-out
javac -cp build/libs/defradb.jar test/DefraTest.java test/DefraApiTest.java -d test-out

# Smoke test: creates and closes an in-memory node
java -cp build/libs/defradb.jar:test-out DefraTest

# API test: exercises every SDK call on in-memory nodes
DEFRA_ALLOW_NON_GO_VERIFIABLE_SIGNING=1 java -Xcheck:jni -cp build/libs/defradb.jar:test-out DefraApiTest
```

`DefraApiTest` prints each failed check and a pass/fail count, and exits non-zero if any check fails. Its remote-signer check signs blocks with a P-256 key; defradb.rs refuses ES256 block signing unless `DEFRA_ALLOW_NON_GO_VERIFIABLE_SIGNING=1` is set, because Go peers cannot verify those signatures. It also checks the calls defradb.rs refuses inside a transaction (document access policies, the print dump, backups) and that truncate applies immediately even through a transaction.

The Android instrumented tests live in `androidTest`. The GitHub Actions workflow builds the AAR, stages it as `androidTest/libs/defradb.aar`, and runs the tests on an API 29 x86_64 emulator.

## Troubleshooting

### `defra.h` is missing

Run the top-level build first; it generates `src/main/c/defra.h` with `cbindgen`.

### `jni.h` cannot be found

Set `JAVA_HOME` to the JDK used for the build. Use a full JDK containing `include/jni.h`. The native build also uses `include/darwin` on macOS or `include/linux` on Linux. Some bundled application runtimes omit these headers.

### Android compiler cannot be found

Set `ANDROID_NDK` to the NDK root. The native build selects `darwin-x86_64` on macOS and `linux-x86_64` on Linux.

### A native library fails to load

Confirm that the artifact was built for the runtime platform and architecture. Android supports `arm64-v8a` and `x86_64`; the desktop JAR contains libraries for the machine on which it was built. An `UnsatisfiedLinkError` naming a native method means `nativewrapper.c` and the Java declarations disagree.
