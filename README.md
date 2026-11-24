# DefraDB Java SDK

[![Build and Test](https://github.com/sourcenetwork/defradb-java-sdk/actions/workflows/build.yml/badge.svg)](https://github.com/sourcenetwork/defradb-java-sdk/actions/workflows/build.yml)
[![Discord](https://img.shields.io/discord/427944769851752448.svg?color=768AD4&label=discord)](https://source.network/discord)

Embed [DefraDB](https://github.com/sourcenetwork/defradb.rs) in Java applications on macOS, Linux, and Android. The SDK exposes DefraDB's collections, documents, queries, transactions, identities, access control, peer-to-peer networking, and other node APIs through a Java interface backed by JNI and the C API of defradb.rs, the Rust implementation of DefraDB.

Read the DefraDB documentation at [docs.source.network](https://docs.source.network/defradb).

> [!WARNING]
> This SDK is experimental and under active development. It aims to track DefraDB's client APIs, but is not yet recommended for production use. Thoroughly test your integration and keep the SDK and DefraDB source revisions aligned.

## Contents

- [Build](#build)
- [Use on macOS or Linux](#use-on-macos-or-linux)
- [Use on Android](#use-on-android)
- [Start a node](#start-a-node)
- [Documentation](#documentation)
- [Community](#community)

## Build

The SDK targets the latest defradb.rs default branch and builds from source together with a local checkout of it. Published Maven or Gradle packages are not yet available.

### Requirements

- macOS or Linux
- Git
- A full JDK 11–20 for desktop builds, with `JAVA_HOME` set (JDK 17 recommended)
- JDK 17 and the Android SDK/NDK for Android builds
- [rustup](https://rustup.rs), which installs the Rust toolchain defradb.rs pins
- `cbindgen` (`cargo install cbindgen`), which generates the C header
- The system packages defradb.rs builds with: `protoc` on both hosts, plus OpenSSL, `pkg-config` and D-Bus development headers on Linux

Clone both repositories, then build for the host or Android:

```shell
git clone https://github.com/sourcenetwork/defradb.rs.git
git clone https://github.com/sourcenetwork/defradb-java-sdk.git
cd defradb-java-sdk

# Linux
./build.sh --defra-dir ../defradb.rs --linux

# macOS
./build.sh --defra-dir ../defradb.rs --macos

# Android; ANDROID_NDK must point to your installed NDK
ANDROID_NDK=/path/to/android-ndk ./build.sh --defra-dir ../defradb.rs --android
```

Select a target explicitly: `--linux`, `--macos`, or `--android`. Desktop targets require a matching host OS. Android can be built from either host and combined with that host’s desktop flag. Gradle 8.3 requires a runtime JDK no newer than 20.

Add `--cleanup` to remove the generated header and native libraries after packaging. `--features` and `--android-features` choose the Cargo features of the native library; see [DEVELOPMENT.md](DEVELOPMENT.md).

Build outputs:

| Target | Artifact | Supported architecture |
| --- | --- | --- |
| macOS / Linux | `build/libs/defradb.jar` | Host architecture |
| Android | `build/outputs/aar/defradb-release.aar` | `arm64-v8a`, `x86_64` |

See [DEVELOPMENT.md](DEVELOPMENT.md) for the manual build process, tests, and troubleshooting.

## Use on macOS or Linux

Add the generated JAR to the compile-time and runtime classpaths:

```shell
javac -cp defradb.jar Example.java
java -cp .:defradb.jar Example
```

The JAR bundles its Java dependency and the native libraries for the selected desktop platform. It must be built for the same operating system and architecture on which it runs. On Linux, the native library links against the system's OpenSSL 3 (`libssl.so.3` and `libcrypto.so.3`), which must be installed where the application runs.

## Use on Android

Copy `defradb-release.aar` into your app module, for example at `app/libs/defradb.aar`, and add it as a dependency:

```groovy
dependencies {
    implementation files('libs/defradb.aar')
}
```

Restrict the app to the ABIs included in the AAR:

```groovy
android {
    defaultConfig {
        minSdk 24

        ndk {
            abiFilters 'arm64-v8a', 'x86_64'
        }
    }
}
```

Android does not let apps use io_uring, so when the SDK loads on Android it sets `NUCLEI_BACKEND=epoll` for DefraDB's network runtime. An app that sets the variable before loading the SDK keeps its own value.

## Start a node

The following creates an in-memory node with peer-to-peer networking turned off. The embedded node never starts an HTTP API server; peer-to-peer networking starts by default unless `disableP2P` is set:

```java
import source.defra.DefraException;
import source.defra.DefraNode;
import source.defra.DefraNodeInitOptions;

public final class Example {
    public static void main(String[] args) {
        DefraNodeInitOptions options = new DefraNodeInitOptions();
        options.inMemory = true;
        options.disableP2P = true;

        try {
            DefraNode node = new DefraNode(options);
            System.out.println(node.getVersion(false, false));
            node.close();
        } catch (DefraException error) {
            System.err.println(error.getMessage());
        }
    }
}
```

`DefraNode` is the main SDK entry point. Its methods return JSON strings for structured DefraDB responses and throw `DefraException` when a native operation fails. Always call `close()` when the node is no longer needed.

## Documentation

- [Architecture](ARCHITECTURE.md) — how Java, JNI, and the defradb.rs C API fit together
- [Development](DEVELOPMENT.md) — build internals, manual builds, tests, and cleanup
- [DefraDB documentation](https://docs.source.network/defradb) — database concepts and DQL
- [Java API sources](src/main/java/source/defra) — currently the authoritative SDK API reference

## Community

Ask questions and share feedback in [Discord](https://source.network/discord) or [GitHub Discussions](https://github.com/sourcenetwork/defradb/discussions). Please report SDK-specific bugs in this repository's [issue tracker](https://github.com/sourcenetwork/defradb-java-sdk/issues).

DefraDB and this SDK are part of the [Source](https://source.network) ecosystem.
