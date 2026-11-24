# Architecture

The DefraDB Java SDK embeds a DefraDB node in the host process. Calls cross two native boundaries before reaching DefraDB:

```text
Java application
    │  source.defra API
    ▼
Java SDK
    │  JNI
    ▼
libnativewrapper
    │  C API (defra.h)
    ▼
libdefradb
    │
    ▼
defradb.rs
```

## Components

- `src/main/java/source/defra` contains the public Java API and native method declarations. `DefraNode` owns the native node handle and is the primary entry point; `DefraTransaction` and `DefraCollection` route the same calls through a transaction or a chosen collection.
- `src/main/c/nativewrapper.c` adapts JNI calls and Java objects to the defradb.rs C API. Each `defra_*` call takes an options struct by value and a single target handle that names either a node or a transaction, so most natives are shared between `DefraNode` and `DefraTransaction`. Results come back as a `DefraResult` (status, error, value) that the wrapper copies into Java and frees.
- `src/main/c/defra.h` is generated at build time by `cbindgen` from defradb.rs's `crates/entry-points/ffi` crate. It is not tracked.
- `libnativewrapper` is compiled by `src/main/c/build.sh` for macOS, Linux, or each supported Android ABI.
- `libdefradb` is the `ffi` crate's library (`libffi`), built by Cargo from the companion [defradb.rs repository](https://github.com/sourcenetwork/defradb.rs) and renamed when staged.
- `build-linux.gradle` and `build-macos.gradle` package Java classes, runtime dependencies, and the platform's shared libraries into a JAR.
- `build-android.gradle` packages Java classes and per-ABI shared libraries into an AAR.

## Callbacks

Some calls hand DefraDB a C callback: asynchronous queries, remote signers (`DefraRemoteSigner`), and replication filters (`DefraReplicationFilter`). DefraDB invokes them on its own threads.

- DefraDB receives numeric handles, never Java references. `NativeCallbacks` keeps the registries that map a handle back to its future, signer, or filter.
- The wrapper attaches a DefraDB thread to the JVM as a daemon on its first callback, and a thread-specific key detaches it as the thread exits. A node's worker threads end when it closes, and Android aborts the process if an attached thread exits.
- No DefraDB thread waits for Java code. Query results are delivered to a future on an SDK thread. Remote signing uses defradb.rs's asynchronous signer form: the wrapper returns immediately, the Java signer runs on an SDK thread, and its signature is handed back with `defra_remote_sign_complete`.
- A registered remote signer stays referenced for the life of the process, because nodes keep signing through it after its identity handles are freed.

## Runtime lifecycle

Constructing `DefraNode` creates an embedded native node and retains its native handle. The node also owns a background dispatcher for subscription and event callbacks. Calling `DefraNode.close()` stops that dispatcher, releases the native node, and releases its replication filter, so applications must close every node they create.

Native libraries are loaded from the packaged artifact by `NativeLoader`. Linux builds locate the paired shared objects beside one another through an `$ORIGIN` runtime search path; macOS uses `@loader_path` and `.dylib` libraries; Android selects libraries from the device ABI directory in the AAR. `JNI_OnLoad` registers every native method and caches the classes the wrapper builds, so a mismatch between the Java declarations and the wrapper fails at load time.

## Source compatibility

The header the wrapper is compiled against and the library it links both come from a particular defradb.rs checkout. Changes to the defradb.rs C API may require corresponding wrapper and Java changes, so build and test the SDK against the defradb.rs revision that the application will use.
