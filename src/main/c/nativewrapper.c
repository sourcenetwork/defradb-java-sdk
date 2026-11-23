// Copyright 2026 Democratized Data Foundation
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.

// The JNI layer between the Java SDK and DefraDB's C API (defra.h, built
// from defradb.rs's ffi crate). Each Java native method is bound here, by
// RegisterNatives in JNI_OnLoad, to one C function that converts its
// arguments, fills the C call's options struct, makes the call, and turns the
// result into its Java object. DefraNode, DefraTransaction and
// DefraCollection declare the same natives for a node, a transaction or a
// collection's store: the C API takes any of these handles in one argument,
// so one C function serves each method in every class that declares it.

// setenv, under -std=c11.
#define _POSIX_C_SOURCE 200809L

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "defra.h"

#define UNUSED(x) (void)(x)

// The result classes and constructors, resolved once in JNI_OnLoad.
static jclass result_class;
static jmethodID result_ctor;
static jclass node_result_class;
static jmethodID node_result_ctor;
static jclass identity_result_class;
static jmethodID identity_result_ctor;
static jclass txn_result_class;
static jmethodID txn_result_ctor;
static jclass string_class;
static jmethodID string_from_bytes;
static jstring utf8_charset;

//=============================================================================
// Strings
//=============================================================================

// A Java String from standard UTF-8 bytes, through new String(byte[],
// "UTF-8"). NewStringUTF takes modified UTF-8, which corrupts the 4-byte
// sequences a value embedding binary data (a lens's WASM module) may hold.
static jstring string_from_utf8(JNIEnv* env, const char* utf8) {
    if (utf8 == NULL) {
        return NULL;
    }
    jsize len = (jsize)strlen(utf8);
    jbyteArray bytes = (*env)->NewByteArray(env, len);
    if (bytes == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte*)utf8);
    jstring string = (jstring)(*env)->NewObject(env, string_class, string_from_bytes, bytes, utf8_charset);
    (*env)->DeleteLocalRef(env, bytes);
    return string;
}

// A malloc'd, NUL-terminated standard UTF-8 copy of `s`; NULL for a null
// `s`, or when the JVM fails (its exception left pending). The counterpart
// of string_from_utf8: GetStringUTFChars gives modified UTF-8.
static char* utf8_from_string(JNIEnv* env, jstring s) {
    if (s == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetStringLength(env, s);
    const jchar* units = (*env)->GetStringChars(env, s, NULL);
    if (units == NULL) {
        return NULL;
    }
    // A UTF-16 unit needs at most 3 UTF-8 bytes; a surrogate pair, two units,
    // needs 4, fewer than the 6 budgeted for it.
    unsigned char* out = (unsigned char*)malloc((size_t)len * 3 + 1);
    if (out == NULL) {
        (*env)->ReleaseStringChars(env, s, units);
        jclass oom = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (oom != NULL) {
            (*env)->ThrowNew(env, oom, "converting a string for DefraDB");
        }
        return NULL;
    }
    size_t n = 0;
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = units[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t low = units[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                i++;
            }
        }
        // An unpaired surrogate has no UTF-8 form: Rust would reject the
        // whole string, so it becomes U+FFFD as Java's own encoder does.
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp <= 0x7F) {
            out[n++] = (unsigned char)cp;
        } else if (cp <= 0x7FF) {
            out[n++] = (unsigned char)(0xC0 | (cp >> 6));
            out[n++] = (unsigned char)(0x80 | (cp & 0x3F));
        } else if (cp <= 0xFFFF) {
            out[n++] = (unsigned char)(0xE0 | (cp >> 12));
            out[n++] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
            out[n++] = (unsigned char)(0x80 | (cp & 0x3F));
        } else {
            out[n++] = (unsigned char)(0xF0 | (cp >> 18));
            out[n++] = (unsigned char)(0x80 | ((cp >> 12) & 0x3F));
            out[n++] = (unsigned char)(0x80 | ((cp >> 6) & 0x3F));
            out[n++] = (unsigned char)(0x80 | (cp & 0x3F));
        }
    }
    out[n] = '\0';
    (*env)->ReleaseStringChars(env, s, units);
    return (char*)out;
}

//=============================================================================
// Arguments: every buffer one call converts, freed together when it ends
//=============================================================================

#define MAX_ARGS 48

typedef struct {
    JNIEnv* env;
    void* owned[MAX_ARGS];
    // The size of each owned buffer that holds key material, wiped before it
    // is freed; 0 for any other.
    size_t secret[MAX_ARGS];
    int count;
    // A conversion failed: a Java exception is pending, and the call returns
    // without calling DefraDB.
    int failed;
} Args;

static void throw_new(JNIEnv* env, const char* class_name, const char* message) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
        (*env)->DeleteLocalRef(env, cls);
    }
}

static void* keep(Args* a, void* buffer) {
    if (buffer == NULL) {
        return NULL;
    }
    if (a->count == MAX_ARGS) {
        free(buffer);
        a->failed = 1;
        throw_new(a->env, "java/lang/IllegalStateException", "a DefraDB call converts more than MAX_ARGS buffers");
        return NULL;
    }
    a->secret[a->count] = 0;
    a->owned[a->count++] = buffer;
    return buffer;
}

// Marks the buffer `keep` stored last as key material of `size` bytes.
static void secret(Args* a, const void* buffer, size_t size) {
    if (buffer != NULL && a->count > 0 && a->owned[a->count - 1] == buffer) {
        a->secret[a->count - 1] = size;
    }
}

// memset through a volatile pointer, so the compiler cannot drop the wipe of
// a buffer that is freed right after.
static void* (*volatile wipe)(void*, int, size_t) = memset;

// `s` as a C string the call owns; NULL for a null `s`.
static const char* str(Args* a, jstring s) {
    if (a->failed || s == NULL) {
        return NULL;
    }
    char* c = utf8_from_string(a->env, s);
    if (c == NULL) {
        a->failed = 1;
        return NULL;
    }
    return keep(a, c);
}

// `bytes` copied into a buffer the call owns, its length in `len`; NULL for
// a null or empty array.
static const uint8_t* bytes_of(Args* a, jbyteArray bytes, int* len) {
    *len = 0;
    if (a->failed || bytes == NULL) {
        return NULL;
    }
    jsize n = (*a->env)->GetArrayLength(a->env, bytes);
    if (n == 0) {
        return NULL;
    }
    uint8_t* copy = (uint8_t*)malloc((size_t)n);
    if (copy == NULL) {
        a->failed = 1;
        throw_new(a->env, "java/lang/OutOfMemoryError", "copying a byte array for DefraDB");
        return NULL;
    }
    (*a->env)->GetByteArrayRegion(a->env, bytes, 0, n, (jbyte*)copy);
    *len = (int)n;
    return keep(a, copy);
}

static void args_free(Args* a) {
    for (int i = 0; i < a->count; i++) {
        if (a->secret[i] != 0) {
            wipe(a->owned[i], 0, a->secret[i]);
        }
        free(a->owned[i]);
    }
    a->count = 0;
}

// Whether the call must return before calling DefraDB: a conversion failed,
// or the JVM has an exception pending. Frees the call's buffers when so, and
// makes sure Java sees an exception rather than a bare null.
static int abandoned(Args* a) {
    if (a->failed || (*a->env)->ExceptionCheck(a->env)) {
        args_free(a);
        if (!(*a->env)->ExceptionCheck(a->env)) {
            throw_new(a->env, "java/lang/IllegalStateException", "converting the arguments of a DefraDB call failed");
        }
        return 1;
    }
    return 0;
}

#define ARGS Args a = { env, { 0 }, { 0 }, 0, 0 }

//=============================================================================
// Java object fields
//=============================================================================

static jfieldID field(Args* a, jclass cls, const char* name, const char* sig) {
    if (a->failed) {
        return NULL;
    }
    jfieldID id = (*a->env)->GetFieldID(a->env, cls, name, sig);
    if (id == NULL) {
        a->failed = 1;
    }
    return id;
}

static const char* field_str(Args* a, jobject obj, jclass cls, const char* name) {
    jfieldID id = field(a, cls, name, "Ljava/lang/String;");
    if (id == NULL) {
        return NULL;
    }
    jstring value = (jstring)(*a->env)->GetObjectField(a->env, obj, id);
    const char* c = str(a, value);
    (*a->env)->DeleteLocalRef(a->env, value);
    return c;
}

static int field_bool(Args* a, jobject obj, jclass cls, const char* name) {
    jfieldID id = field(a, cls, name, "Z");
    return id == NULL ? 0 : ((*a->env)->GetBooleanField(a->env, obj, id) ? 1 : 0);
}

static int field_int(Args* a, jobject obj, jclass cls, const char* name) {
    jfieldID id = field(a, cls, name, "I");
    return id == NULL ? 0 : (int)(*a->env)->GetIntField(a->env, obj, id);
}

static int64_t field_long(Args* a, jobject obj, jclass cls, const char* name) {
    jfieldID id = field(a, cls, name, "J");
    return id == NULL ? 0 : (int64_t)(*a->env)->GetLongField(a->env, obj, id);
}

static const uint8_t* field_bytes(Args* a, jobject obj, jclass cls, const char* name, int* len) {
    *len = 0;
    jfieldID id = field(a, cls, name, "[B");
    if (id == NULL) {
        return NULL;
    }
    jbyteArray value = (jbyteArray)(*a->env)->GetObjectField(a->env, obj, id);
    const uint8_t* copy = bytes_of(a, value, len);
    (*a->env)->DeleteLocalRef(a->env, value);
    return copy;
}

static void throw_null(JNIEnv* env, const char* message) {
    jclass npe = (*env)->FindClass(env, "java/lang/NullPointerException");
    if (npe != NULL) {
        (*env)->ThrowNew(env, npe, message);
    }
}

// The collection a DefraCollectionOptions selects, and its signing choice.
typedef struct {
    const char* name;
    const char* version_id;
    const char* collection_id;
    int get_inactive;
    // 0 for the node's default, 1 to sign, -1 not to: the C API's encoding,
    // and the Java field's null, true and false.
    int signing;
} Collection;

static Collection collection(Args* a, jobject options) {
    Collection c = { NULL, NULL, NULL, 0, 0 };
    if (a->failed) {
        return c;
    }
    if (options == NULL) {
        throw_null(a->env, "collection options must not be null");
        a->failed = 1;
        return c;
    }
    jclass cls = (*a->env)->GetObjectClass(a->env, options);
    c.name = field_str(a, options, cls, "name");
    c.version_id = field_str(a, options, cls, "version");
    c.collection_id = field_str(a, options, cls, "collectionID");
    c.get_inactive = field_bool(a, options, cls, "getInactive");
    jfieldID signing = field(a, cls, "enableSigning", "Ljava/lang/Boolean;");
    if (signing != NULL) {
        jobject value = (*a->env)->GetObjectField(a->env, options, signing);
        if (value != NULL) {
            jclass boolean = (*a->env)->GetObjectClass(a->env, value);
            jmethodID get = (*a->env)->GetMethodID(a->env, boolean, "booleanValue", "()Z");
            if (get == NULL) {
                a->failed = 1;
            } else {
                c.signing = (*a->env)->CallBooleanMethod(a->env, value, get) ? 1 : -1;
            }
            (*a->env)->DeleteLocalRef(a->env, boolean);
            (*a->env)->DeleteLocalRef(a->env, value);
        }
    }
    (*a->env)->DeleteLocalRef(a->env, cls);
    return c;
}

//=============================================================================
// Results
//=============================================================================

static jobject new_result(JNIEnv* env, int status, const char* error, const char* value) {
    jstring error_string = string_from_utf8(env, error);
    jstring value_string = string_from_utf8(env, value);
    if ((*env)->ExceptionCheck(env)) {
        return NULL;
    }
    jobject result = (*env)->NewObject(env, result_class, result_ctor, (jint)status, error_string, value_string);
    (*env)->DeleteLocalRef(env, error_string);
    (*env)->DeleteLocalRef(env, value_string);
    return result;
}

// `res` as a DefraResult, freed with the C API's own free function.
static jobject result(JNIEnv* env, struct DefraResult res) {
    jobject converted = new_result(env, res.status, res.error, res.value);
    defra_result_free(&res);
    return converted;
}

// A DefraResult for a call the wrapper refuses before reaching DefraDB.
static jobject refused(JNIEnv* env, const char* message) {
    return new_result(env, 1, message, "");
}

static jobject handle_result(JNIEnv* env, jclass cls, jmethodID ctor, int status, const char* error, uintptr_t handle) {
    jstring error_string = string_from_utf8(env, error);
    if ((*env)->ExceptionCheck(env)) {
        return NULL;
    }
    jobject converted = (*env)->NewObject(env, cls, ctor, (jint)status, error_string, (jlong)handle);
    (*env)->DeleteLocalRef(env, error_string);
    return converted;
}

// Each handle result below closes the handle DefraDB made when Java cannot
// receive it, so no node, identity or transaction is left without an owner.

static jobject node_result(JNIEnv* env, struct DefraNewNodeResult res) {
    jobject converted = handle_result(env, node_result_class, node_result_ctor, res.status, res.error, res.node_ptr);
    if (converted == NULL && res.node_ptr != 0) {
        struct DefraResult closed = defra_node_close(res.node_ptr);
        defra_result_free(&closed);
    }
    defra_new_node_result_free(&res);
    return converted;
}

static jobject identity_result(JNIEnv* env, struct DefraNewIdentityResult res) {
    jobject converted = handle_result(env, identity_result_class, identity_result_ctor, res.status, res.error, res.identity_ptr);
    if (converted == NULL && res.identity_ptr != 0) {
        defra_identity_free(res.identity_ptr);
    }
    defra_new_identity_result_free(&res);
    return converted;
}

static jobject txn_result(JNIEnv* env, struct DefraNewTxnResult res) {
    jobject converted = handle_result(env, txn_result_class, txn_result_ctor, res.status, res.error, res.txn_ptr);
    if (converted == NULL && res.txn_ptr != 0) {
        defra_transaction_discard(res.txn_ptr);
    }
    defra_new_txn_result_free(&res);
    return converted;
}

#define H(x) ((uintptr_t)(x))
#define B(x) ((x) ? 1 : 0)

//=============================================================================
// Callbacks from DefraDB into Java
//=============================================================================

// DefraDB calls these on its own threads, which the JVM does not know until
// they attach. A thread attaches, as a daemon, on its first callback and
// stays attached while it lives: attaching once is cheaper than attaching for
// every call. DefraDB's threads do end (a node's worker pool joins its
// threads when the node closes), and ART aborts the process when an attached
// thread exits, so a thread-specific key detaches each thread the wrapper
// attached as it exits. The Java side (NativeCallbacks) passes anything slow
// to its own threads, so a DefraDB thread never waits on application code.

static JavaVM* jvm;
static jclass callbacks_class;
static jmethodID callbacks_deliver_query;
static jmethodID callbacks_sign;
static jmethodID callbacks_keep;
static pthread_key_t detach_key;

// The destructor of detach_key: runs as a thread the wrapper attached exits.
static void detach_thread(void* env) {
    UNUSED(env);
    (*jvm)->DetachCurrentThread(jvm);
}

// The current thread's JNIEnv, attaching the thread first if DefraDB made it.
static JNIEnv* attached_env(void) {
    JNIEnv* env;
    if ((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
#ifdef __ANDROID__
    if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, &env, NULL) != JNI_OK) {
#else
    if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, (void**)&env, NULL) != JNI_OK) {
#endif
        return NULL;
    }
    // The destructor runs only for a non-null value; the env is one.
    if (pthread_setspecific(detach_key, env) != 0) {
        (*jvm)->DetachCurrentThread(jvm);
        return NULL;
    }
    return env;
}

// Clears an exception a Java callback left, so it cannot leak into the next
// call on this thread; true when there was one.
static int clear_exception(JNIEnv* env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

// A DefraResultCallback: hands an asynchronous query's result to the future
// waiting under `context`, and frees the result. DefraDB calls it exactly
// once, so it always completes the future: a result Java cannot receive is
// delivered as a failure with no message, which NativeCallbacks reports as a
// conversion failure. Only a thread the JVM refuses to attach cannot reach
// Java at all.
static void on_query_result(uintptr_t context, struct DefraResult res) {
    JNIEnv* env = attached_env();
    if (env != NULL) {
        int framed = (*env)->PushLocalFrame(env, 4) == 0;
        jint status = (jint)res.status;
        jstring error = NULL;
        jstring value = NULL;
        if (framed) {
            error = string_from_utf8(env, res.error);
            value = string_from_utf8(env, res.value);
        }
        if (clear_exception(env) || !framed) {
            status = 1;
            error = NULL;
            value = NULL;
        }
        (*env)->CallStaticVoidMethod(env, callbacks_class, callbacks_deliver_query,
            (jlong)context, status, error, value);
        clear_exception(env);
        if (framed) {
            (*env)->PopLocalFrame(env, NULL);
        }
    }
    defra_result_free(&res);
}

// A DefraRemoteSignAsyncCallback: starts the signature on the Java signer
// `signer_handle` names, returning 0 once it has started and 1 when it could
// not. Java completes it later with defra_remote_sign_complete, through
// sign_complete below.
static int on_sign(uintptr_t signer_handle, const char* did, const char* key_type, const uint8_t* payload, uintptr_t payload_len, uintptr_t completion) {
    JNIEnv* env = attached_env();
    if (env == NULL) {
        return 1;
    }
    if ((*env)->PushLocalFrame(env, 4) != 0) {
        clear_exception(env);
        return 1;
    }
    int status = 1;
    jstring did_string = string_from_utf8(env, did);
    jstring key_type_string = string_from_utf8(env, key_type);
    jbyteArray payload_bytes = (*env)->NewByteArray(env, (jsize)payload_len);
    if (payload_bytes != NULL) {
        (*env)->SetByteArrayRegion(env, payload_bytes, 0, (jsize)payload_len, (const jbyte*)payload);
    }
    if (!clear_exception(env)) {
        status = (int)(*env)->CallStaticIntMethod(env, callbacks_class, callbacks_sign,
            (jlong)signer_handle, did_string, key_type_string, payload_bytes, (jlong)completion);
        if (clear_exception(env)) {
            status = 1;
        }
    }
    (*env)->PopLocalFrame(env, NULL);
    return status;
}

// A DefraReplicationFilterCallback: asks the Java filter `context` names
// whether to keep a replicated document. A failure refuses it.
static int on_replication_filter(uintptr_t context, const char* collection_id, const char* collection_name, const char* doc_id, const char* fields_json) {
    JNIEnv* env = attached_env();
    if (env == NULL) {
        return 0;
    }
    if ((*env)->PushLocalFrame(env, 4) != 0) {
        clear_exception(env);
        return 0;
    }
    int keep = 0;
    jstring id = string_from_utf8(env, collection_id);
    jstring name = string_from_utf8(env, collection_name);
    jstring doc = string_from_utf8(env, doc_id);
    jstring fields = string_from_utf8(env, fields_json);
    if (!clear_exception(env)) {
        keep = (*env)->CallStaticBooleanMethod(env, callbacks_class, callbacks_keep,
            (jlong)context, id, name, doc, fields) ? 1 : 0;
        if (clear_exception(env)) {
            keep = 0;
        }
    }
    (*env)->PopLocalFrame(env, NULL);
    return keep;
}

// NativeCallbacks.CompleteSignNative: reports a signature Java finished.
static jint sign_complete(JNIEnv* env, jclass cls, jlong completion, jint status, jbyteArray signature) {
    UNUSED(cls);
    ARGS;
    int len = 0;
    const uint8_t* bytes = status == 0 ? bytes_of(&a, signature, &len) : NULL;
    if (a.failed) {
        args_free(&a);
        (*env)->ExceptionClear(env);
        return (jint)defra_remote_sign_complete(H(completion), 1, NULL, 0);
    }
    jint delivered = (jint)defra_remote_sign_complete(H(completion), (int)status, bytes, (uintptr_t)len);
    args_free(&a);
    return delivered;
}

// The profiling calls exist only in a library built with DefraDB's
// `profiling` feature. Referenced weakly, they are null in any other, and a
// library without them still loads.
extern struct DefraResult defra_profiling_start(const char* output_path) __attribute__((weak));
extern struct DefraResult defra_profiling_stop(void) __attribute__((weak));

//=============================================================================
// Access control
//=============================================================================

static jobject acp_dac_policy_add(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring policy) {
    UNUSED(thiz);
    ARGS;
    const char* policy_c = str(&a, policy);
    if (abandoned(&a)) return NULL;
    DefraDacPolicyAddOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_acp_dac_policy_add(H(target), policy_c, opts));
    args_free(&a);
    return res;
}

static jobject dac_relationship(JNIEnv* env, jlong target, jlong identity, jstring collection_name, jstring doc_id, jstring relation, jstring actor, int delete) {
    ARGS;
    const char* collection_c = str(&a, collection_name);
    const char* doc_id_c = str(&a, doc_id);
    const char* relation_c = str(&a, relation);
    const char* actor_c = str(&a, actor);
    if (abandoned(&a)) return NULL;
    DefraDacRelationshipOptions opts = { .identity = H(identity) };
    struct DefraResult res = delete
        ? defra_acp_dac_relationship_delete(H(target), collection_c, doc_id_c, relation_c, actor_c, opts)
        : defra_acp_dac_relationship_add(H(target), collection_c, doc_id_c, relation_c, actor_c, opts);
    jobject converted = result(env, res);
    args_free(&a);
    return converted;
}

static jobject acp_dac_relationship_add(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring collection_name, jstring doc_id, jstring relation, jstring actor) {
    UNUSED(thiz);
    return dac_relationship(env, target, identity, collection_name, doc_id, relation, actor, 0);
}

static jobject acp_dac_relationship_delete(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring collection_name, jstring doc_id, jstring relation, jstring actor) {
    UNUSED(thiz);
    return dac_relationship(env, target, identity, collection_name, doc_id, relation, actor, 1);
}

static jobject acp_nac_disable(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraNacOptions opts = { .identity = H(identity) };
    return result(env, defra_acp_nac_disable(H(target), opts));
}

static jobject acp_nac_re_enable(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraNacOptions opts = { .identity = H(identity) };
    return result(env, defra_acp_nac_re_enable(H(target), opts));
}

static jobject acp_nac_status(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraNacOptions opts = { .identity = H(identity) };
    return result(env, defra_acp_nac_status(H(target), opts));
}

static jobject nac_relationship(JNIEnv* env, jlong target, jlong identity, jstring relation, jstring actor, int delete) {
    ARGS;
    const char* relation_c = str(&a, relation);
    const char* actor_c = str(&a, actor);
    if (abandoned(&a)) return NULL;
    DefraNacRelationshipOptions opts = { .identity = H(identity) };
    struct DefraResult res = delete
        ? defra_acp_nac_relationship_delete(H(target), relation_c, actor_c, opts)
        : defra_acp_nac_relationship_add(H(target), relation_c, actor_c, opts);
    jobject converted = result(env, res);
    args_free(&a);
    return converted;
}

static jobject acp_nac_relationship_add(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring relation, jstring actor) {
    UNUSED(thiz);
    return nac_relationship(env, target, identity, relation, actor, 0);
}

static jobject acp_nac_relationship_delete(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring relation, jstring actor) {
    UNUSED(thiz);
    return nac_relationship(env, target, identity, relation, actor, 1);
}

static jobject action_list(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraActionListOptions opts = { .identity = H(identity) };
    return result(env, defra_action_list(H(target), opts));
}

//=============================================================================
// Backup, blocks and the store dump
//=============================================================================

static jobject backup_export(JNIEnv* env, jobject thiz, jlong target, jstring filepath, jstring collections, jstring format, jboolean pretty) {
    UNUSED(thiz);
    ARGS;
    const char* filepath_c = str(&a, filepath);
    DefraBackupExportOptions opts = {
        .collections = str(&a, collections),
        .format = str(&a, format),
        .pretty = B(pretty),
    };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_backup_export(H(target), filepath_c, opts));
    args_free(&a);
    return res;
}

static jobject backup_import(JNIEnv* env, jobject thiz, jlong target, jstring filepath) {
    UNUSED(thiz);
    ARGS;
    const char* filepath_c = str(&a, filepath);
    if (abandoned(&a)) return NULL;
    DefraBackupImportOptions opts = { .identity = 0 };
    jobject res = result(env, defra_backup_import(H(target), filepath_c, opts));
    args_free(&a);
    return res;
}

static jobject block_verify(JNIEnv* env, jobject thiz, jlong target, jstring key_type, jstring public_key, jstring cid, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* key_type_c = str(&a, key_type);
    const char* public_key_c = str(&a, public_key);
    const char* cid_c = str(&a, cid);
    if (abandoned(&a)) return NULL;
    DefraBlockVerifyOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_block_verify(H(target), cid_c, key_type_c, public_key_c, opts));
    args_free(&a);
    return res;
}

static jobject print_dump(JNIEnv* env, jobject thiz, jlong target) {
    UNUSED(thiz);
    DefraPrintDumpOptions opts = { .identity = 0 };
    return result(env, defra_print_dump(H(target), opts));
}

//=============================================================================
// Collections
//=============================================================================

static jobject collection_add(JNIEnv* env, jobject thiz, jlong target, jstring sdl, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* sdl_c = str(&a, sdl);
    if (abandoned(&a)) return NULL;
    DefraCollectionAddOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_collection_add(H(target), sdl_c, opts));
    args_free(&a);
    return res;
}

static jobject collection_describe(JNIEnv* env, jobject thiz, jlong target, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraCollectionDescribeOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
    };
    jobject res = result(env, defra_collection_describe(H(target), opts));
    args_free(&a);
    return res;
}

static jobject collection_patch(JNIEnv* env, jobject thiz, jlong target, jstring patch, jstring lens_config, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* patch_c = str(&a, patch);
    DefraCollectionPatchOptions opts = { .identity = H(identity), .lens_config = str(&a, lens_config) };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_collection_patch(H(target), patch_c, opts));
    args_free(&a);
    return res;
}

static jobject collection_set_active(JNIEnv* env, jobject thiz, jlong target, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraCollectionSetActiveOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_collection_set_active(H(target), c.version_id, opts));
    args_free(&a);
    return res;
}

static jobject collection_truncate(JNIEnv* env, jobject thiz, jlong target, jstring filter, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* filter_c = str(&a, filter);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraCollectionTruncateOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .filter_json = filter_c,
    };
    jobject res = result(env, defra_collection_truncate(H(target), opts));
    args_free(&a);
    return res;
}

static jobject collection_delete(JNIEnv* env, jobject thiz, jlong target, jstring names, jint active_only, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* names_c = str(&a, names);
    if (abandoned(&a)) return NULL;
    DefraCollectionDeleteOptions opts = { .identity = H(identity), .active_only = (int)active_only };
    jobject res = result(env, defra_collection_delete(H(target), names_c, opts));
    args_free(&a);
    return res;
}

//=============================================================================
// Documents
//=============================================================================

static jobject document_add(JNIEnv* env, jobject thiz, jlong target, jstring json, jboolean encrypt, jstring encrypted_fields, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* json_c = str(&a, json);
    const char* fields_c = str(&a, encrypted_fields);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraDocumentAddOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .encrypt = B(encrypt),
        .encrypted_fields = fields_c,
        .signing = c.signing,
    };
    jobject res = result(env, defra_document_add(H(target), json_c, opts));
    args_free(&a);
    return res;
}

static jobject document_delete(JNIEnv* env, jobject thiz, jlong target, jstring doc_id, jstring filter, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* doc_id_c = str(&a, doc_id);
    const char* filter_c = str(&a, filter);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraDocumentDeleteOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .doc_id = doc_id_c,
        .filter_json = filter_c,
        .signing = c.signing,
    };
    jobject res = result(env, defra_document_delete(H(target), opts));
    args_free(&a);
    return res;
}

static jobject document_get(JNIEnv* env, jobject thiz, jlong target, jstring doc_id, jboolean show_deleted, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* doc_id_c = str(&a, doc_id);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraDocumentGetOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .show_deleted = B(show_deleted),
    };
    jobject res = result(env, defra_document_get(H(target), doc_id_c, opts));
    args_free(&a);
    return res;
}

static jobject document_update(JNIEnv* env, jobject thiz, jlong target, jstring doc_id, jstring filter, jstring updater, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* doc_id_c = str(&a, doc_id);
    const char* filter_c = str(&a, filter);
    const char* updater_c = str(&a, updater);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraDocumentUpdateOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .doc_id = doc_id_c,
        .filter_json = filter_c,
        .signing = c.signing,
    };
    jobject res = result(env, defra_document_update(H(target), updater_c, opts));
    args_free(&a);
    return res;
}

//=============================================================================
// Indexes
//=============================================================================

static jobject encrypted_index_delete(JNIEnv* env, jobject thiz, jlong target, jstring collection_name, jstring field_name, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* field_c = str(&a, field_name);
    DefraEncryptedIndexDeleteOptions opts = { .identity = H(identity), .collection_name = str(&a, collection_name) };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_encrypted_index_delete(H(target), field_c, opts));
    args_free(&a);
    return res;
}

static jobject encrypted_index_list(JNIEnv* env, jobject thiz, jlong target, jstring collection_name, jlong identity) {
    UNUSED(thiz);
    ARGS;
    DefraEncryptedIndexListOptions opts = { .identity = H(identity), .collection_name = str(&a, collection_name) };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_encrypted_index_list(H(target), opts));
    args_free(&a);
    return res;
}

static jobject encrypted_index_new(JNIEnv* env, jobject thiz, jlong target, jstring collection_name, jstring field_name, jstring index_type, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* field_c = str(&a, field_name);
    DefraEncryptedIndexNewOptions opts = {
        .identity = H(identity),
        .collection_name = str(&a, collection_name),
        .index_type = str(&a, index_type),
    };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_encrypted_index_new(H(target), field_c, opts));
    args_free(&a);
    return res;
}

static jobject index_delete(JNIEnv* env, jobject thiz, jlong target, jstring name, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* name_c = str(&a, name);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraIndexDeleteOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
    };
    jobject res = result(env, defra_index_delete(H(target), name_c, opts));
    args_free(&a);
    return res;
}

static jobject index_list(JNIEnv* env, jobject thiz, jlong target, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraIndexListOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
    };
    jobject res = result(env, defra_index_list(H(target), opts));
    args_free(&a);
    return res;
}

static jobject index_new(JNIEnv* env, jobject thiz, jlong target, jstring name, jstring fields, jboolean unique, jstring vector_json, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* name_c = str(&a, name);
    const char* fields_c = str(&a, fields);
    const char* vector_c = str(&a, vector_json);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraIndexNewOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .name = name_c,
        .unique = B(unique),
        .vector_json = vector_c,
    };
    jobject res = result(env, defra_index_new(H(target), fields_c, opts));
    args_free(&a);
    return res;
}

//=============================================================================
// Identities
//=============================================================================

static jobject identity_export_private_key(JNIEnv* env, jobject thiz, jlong identity) {
    UNUSED(thiz);
    return result(env, defra_identity_export_private_key(H(identity)));
}

static void identity_free(JNIEnv* env, jobject thiz, jlong identity) {
    UNUSED(env);
    UNUSED(thiz);
    defra_identity_free(H(identity));
}

static jobject identity_new(JNIEnv* env, jobject thiz, jstring key_type) {
    UNUSED(thiz);
    ARGS;
    DefraIdentityNewOptions opts = { .key_type = str(&a, key_type) };
    if (abandoned(&a)) return NULL;
    jobject res = identity_result(env, defra_identity_new(opts));
    args_free(&a);
    return res;
}

static jobject identity_new_from_private_key(JNIEnv* env, jobject thiz, jstring private_key_hex) {
    UNUSED(thiz);
    ARGS;
    const char* key_c = str(&a, private_key_hex);
    secret(&a, key_c, key_c == NULL ? 0 : strlen(key_c));
    if (abandoned(&a)) return NULL;
    DefraIdentityNewOptions opts = { .key_type = NULL };
    jobject res = identity_result(env, defra_identity_new_from_private_key(key_c, opts));
    args_free(&a);
    return res;
}

static jobject node_identity(JNIEnv* env, jobject thiz, jlong target) {
    UNUSED(thiz);
    return result(env, defra_node_identity(H(target)));
}

//=============================================================================
// Lenses and views
//=============================================================================

static jobject lens_add(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring config) {
    UNUSED(thiz);
    ARGS;
    const char* config_c = str(&a, config);
    if (abandoned(&a)) return NULL;
    DefraLensOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_lens_add(H(target), config_c, opts));
    args_free(&a);
    return res;
}

static jobject lens_list(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraLensOptions opts = { .identity = H(identity) };
    return result(env, defra_lens_list(H(target), opts));
}

static jobject lens_set(JNIEnv* env, jobject thiz, jlong target, jlong identity, jstring src, jstring dst, jstring config) {
    UNUSED(thiz);
    ARGS;
    const char* src_c = str(&a, src);
    const char* dst_c = str(&a, dst);
    const char* config_c = str(&a, config);
    if (abandoned(&a)) return NULL;
    DefraLensOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_lens_set(H(target), src_c, dst_c, config_c, opts));
    args_free(&a);
    return res;
}

static jobject view_add(JNIEnv* env, jobject thiz, jlong target, jstring query, jstring sdl, jstring transform_cid, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* query_c = str(&a, query);
    const char* sdl_c = str(&a, sdl);
    DefraViewAddOptions opts = { .identity = H(identity), .transform_cid = str(&a, transform_cid) };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_view_add(H(target), query_c, sdl_c, opts));
    args_free(&a);
    return res;
}

static jobject view_refresh(JNIEnv* env, jobject thiz, jlong target, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraViewRefreshOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
    };
    jobject res = result(env, defra_view_refresh(H(target), opts));
    args_free(&a);
    return res;
}

//=============================================================================
// Nodes
//=============================================================================

static jobject node_new(JNIEnv* env, jobject thiz, jobject options, jlong replication_filter) {
    UNUSED(thiz);
    if (options == NULL) {
        throw_null(env, "node init options must not be null");
        return NULL;
    }
    ARGS;
    jclass cls = (*env)->GetObjectClass(env, options);
    DefraNodeInitOptions opts;
    memset(&opts, 0, sizeof opts);

    opts.db_path = field_str(&a, options, cls, "dbPath");
    opts.listening_addresses = field_str(&a, options, cls, "listeningAddresses");
    opts.replicator_retry_intervals = field_str(&a, options, cls, "replicatorRetryIntervals");
    opts.peers = field_str(&a, options, cls, "peers");
    jfieldID identity_field = field(&a, cls, "identity", "Lsource/defra/DefraIdentity;");
    if (identity_field != NULL) {
        jobject identity = (*env)->GetObjectField(env, options, identity_field);
        if (identity != NULL) {
            jclass identity_cls = (*env)->GetObjectClass(env, identity);
            opts.identity_ptr = (uintptr_t)field_long(&a, identity, identity_cls, "ptr");
            (*env)->DeleteLocalRef(env, identity_cls);
            (*env)->DeleteLocalRef(env, identity);
        }
    }
    opts.in_memory = field_bool(&a, options, cls, "inMemory");
    opts.disable_p2p = field_bool(&a, options, cls, "disableP2P");
    opts.enable_node_acp = field_bool(&a, options, cls, "enableNodeACP");
    opts.max_transaction_retries = field_int(&a, options, cls, "maxTransactionRetries");

    opts.store_type = field_str(&a, options, cls, "storeType");
    opts.store_file_size = field_long(&a, options, cls, "badgerFileSize");
    opts.store_encryption_key = field_bytes(&a, options, cls, "badgerEncryptionKey", &opts.store_encryption_key_len);
    secret(&a, opts.store_encryption_key, (size_t)opts.store_encryption_key_len);

    // enableSigning, from Go's options, has no effect: false was Go's
    // default, which signs, and true asked for the same.
    opts.disable_signing = field_bool(&a, options, cls, "disableSigning");
    opts.searchable_encryption_key = field_bytes(&a, options, cls, "searchableEncryptionKey", &opts.searchable_encryption_key_len);
    secret(&a, opts.searchable_encryption_key, (size_t)opts.searchable_encryption_key_len);
    opts.p2p_block_sync_timeout_ms = field_long(&a, options, cls, "p2pBlockSyncTimeoutMs");
    opts.lens_pool_size = field_int(&a, options, cls, "lensPoolSize");
    opts.chunk_size = field_int(&a, options, cls, "chunkSize");

    opts.enable_pub_sub = field_bool(&a, options, cls, "enablePubSub");
    opts.enable_relay = field_bool(&a, options, cls, "enableRelay");
    opts.enable_clear_backoff_on_retry = field_bool(&a, options, cls, "enableClearBackoffOnRetry");
    opts.p2p_private_key = field_bytes(&a, options, cls, "p2pPrivateKey", &opts.p2p_private_key_len);
    secret(&a, opts.p2p_private_key, (size_t)opts.p2p_private_key_len);

    opts.http_address = field_str(&a, options, cls, "httpAddress");
    opts.http_allowed_origins = field_str(&a, options, cls, "httpAllowedOrigins");
    opts.tls_cert_path = field_str(&a, options, cls, "tlsCertPath");
    opts.tls_key_path = field_str(&a, options, cls, "tlsKeyPath");
    opts.http_read_timeout_ms = field_long(&a, options, cls, "httpReadTimeoutMs");
    opts.http_write_timeout_ms = field_long(&a, options, cls, "httpWriteTimeoutMs");
    opts.http_idle_timeout_ms = field_long(&a, options, cls, "httpIdleTimeoutMs");

    opts.document_acp_type = field_str(&a, options, cls, "documentACPType");
    opts.document_acp_path = field_str(&a, options, cls, "documentACPPath");
    opts.remote_dac_log_id = field_str(&a, options, cls, "remoteDACLogID");
    opts.remote_dac_grpc_address = field_str(&a, options, cls, "remoteDACGRPCAddress");
    opts.remote_dac_comet_rpc_address = field_str(&a, options, cls, "remoteDACCometRPCAddress");
    opts.node_acp_path = field_str(&a, options, cls, "nodeACPPath");

    opts.vera_chain_id = field_str(&a, options, cls, "veraChainID");
    opts.vera_signer_key = field_bytes(&a, options, cls, "veraSignerKey", &opts.vera_signer_key_len);
    secret(&a, opts.vera_signer_key, (size_t)opts.vera_signer_key_len);
    opts.p2p_transport = field_str(&a, options, cls, "p2pTransport");
    opts.iroh_relay_url = field_str(&a, options, cls, "irohRelayURL");
    opts.iroh_relay_mode = field_str(&a, options, cls, "irohRelayMode");
    opts.iroh_relay_urls_json = field_str(&a, options, cls, "irohRelayURLsJSON");
    opts.iroh_bind_addr = field_str(&a, options, cls, "irohBindAddress");
    opts.iroh_bind_port = (uint16_t)field_int(&a, options, cls, "irohBindPort");
    opts.iroh_disable_discovery = field_bool(&a, options, cls, "irohDisableDiscovery");
    opts.iroh_discovery_origin_domain = field_str(&a, options, cls, "irohDiscoveryOriginDomain");
    opts.iroh_pkarr_relay_url = field_str(&a, options, cls, "irohPkarrRelayURL");
    opts.iroh_key_path = field_str(&a, options, cls, "irohKeyPath");
    opts.max_concurrent_dag_fetches = (uintptr_t)field_long(&a, options, cls, "maxConcurrentDAGFetches");
    opts.max_concurrent_push_tasks = (uintptr_t)field_long(&a, options, cls, "maxConcurrentPushTasks");
    opts.max_doc_sync_request_doc_ids = (uintptr_t)field_long(&a, options, cls, "maxDocSyncRequestDocIDs");
    opts.rate_limit_burst = (uint32_t)field_int(&a, options, cls, "rateLimitBurst");
    jfieldID rate = field(&a, cls, "rateLimitRate", "D");
    opts.rate_limit_rate = rate == NULL ? 0 : (double)(*env)->GetDoubleField(env, options, rate);
    if (replication_filter != 0) {
        opts.replication_filter = on_replication_filter;
        opts.replication_filter_context = H(replication_filter);
    }
    opts.p2p_resource_memory_mib = (uint64_t)field_long(&a, options, cls, "p2pResourceMemoryMiB");
    opts.p2p_resource_file_descriptors = (uint64_t)field_long(&a, options, cls, "p2pResourceFileDescriptors");
    opts.p2p_max_streams_per_peer = (uintptr_t)field_long(&a, options, cls, "p2pMaxStreamsPerPeer");
    opts.disable_p2p_listen = field_bool(&a, options, cls, "disableP2PListen");
    (*env)->DeleteLocalRef(env, cls);

    if (abandoned(&a)) return NULL;
    jobject res = node_result(env, defra_node_new(opts));
    args_free(&a);
    return res;
}

static jobject node_close(JNIEnv* env, jobject thiz, jlong node) {
    UNUSED(thiz);
    return result(env, defra_node_close(H(node)));
}

static jobject node_options(JNIEnv* env, jobject thiz, jlong node) {
    UNUSED(thiz);
    return result(env, defra_node_options(H(node)));
}

static jobject version_get(JNIEnv* env, jobject thiz, jboolean full, jboolean json) {
    UNUSED(thiz);
    DefraVersionOptions opts = { .full = B(full), .json = B(json) };
    return result(env, defra_version_get(opts));
}

//=============================================================================
// Peer-to-peer
//=============================================================================

typedef struct DefraResult (*P2PCall)(uintptr_t, DefraP2POptions);
typedef struct DefraResult (*P2PNamesCall)(uintptr_t, const char*, DefraP2POptions);

static jobject p2p_call(JNIEnv* env, jlong target, jlong identity, P2PCall call) {
    DefraP2POptions opts = { .identity = H(identity) };
    return result(env, call(H(target), opts));
}

static jobject p2p_names_call(JNIEnv* env, jlong target, jstring names, jlong identity, P2PNamesCall call) {
    ARGS;
    const char* names_c = str(&a, names);
    if (abandoned(&a)) return NULL;
    DefraP2POptions opts = { .identity = H(identity) };
    jobject res = result(env, call(H(target), names_c, opts));
    args_free(&a);
    return res;
}

// The inline wrappers in defra.h, as functions one table can name.
static struct DefraResult call_collection_add(uintptr_t t, const char* n, DefraP2POptions o) { return defra_p2p_collection_add(t, n, o); }
static struct DefraResult call_collection_delete(uintptr_t t, const char* n, DefraP2POptions o) { return defra_p2p_collection_delete(t, n, o); }
static struct DefraResult call_document_add(uintptr_t t, const char* n, DefraP2POptions o) { return defra_p2p_document_add(t, n, o); }
static struct DefraResult call_document_delete(uintptr_t t, const char* n, DefraP2POptions o) { return defra_p2p_document_delete(t, n, o); }
static struct DefraResult call_connect(uintptr_t t, const char* n, DefraP2POptions o) { return defra_p2p_connect(t, n, o); }
static struct DefraResult call_disconnect(uintptr_t t, const char* n, DefraP2POptions o) { return defra_p2p_disconnect(t, n, o); }
static struct DefraResult call_collection_list(uintptr_t t, DefraP2POptions o) { return defra_p2p_collection_list(t, o); }
static struct DefraResult call_document_list(uintptr_t t, DefraP2POptions o) { return defra_p2p_document_list(t, o); }
static struct DefraResult call_info(uintptr_t t, DefraP2POptions o) { return defra_p2p_info(t, o); }
static struct DefraResult call_peer_list(uintptr_t t, DefraP2POptions o) { return defra_p2p_peer_list(t, o); }
static struct DefraResult call_replicator_list(uintptr_t t, DefraP2POptions o) { return defra_p2p_replicator_list(t, o); }

static jobject p2p_collection_add(JNIEnv* env, jobject thiz, jlong target, jstring collections, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, collections, identity, call_collection_add);
}

static jobject p2p_collection_delete(JNIEnv* env, jobject thiz, jlong target, jstring collections, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, collections, identity, call_collection_delete);
}

static jobject p2p_collection_list(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_collection_list);
}

static jobject p2p_document_add(JNIEnv* env, jobject thiz, jlong target, jstring doc_ids, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, doc_ids, identity, call_document_add);
}

static jobject p2p_document_delete(JNIEnv* env, jobject thiz, jlong target, jstring doc_ids, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, doc_ids, identity, call_document_delete);
}

static jobject p2p_document_list(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_document_list);
}

static jobject p2p_connect(JNIEnv* env, jobject thiz, jlong target, jstring addresses, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, addresses, identity, call_connect);
}

static jobject p2p_disconnect(JNIEnv* env, jobject thiz, jlong target, jstring addresses, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, addresses, identity, call_disconnect);
}

static jobject p2p_info(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_info);
}

static jobject p2p_peer_list(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_peer_list);
}

static jobject p2p_replicator_list(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_replicator_list);
}

static jobject p2p_replicator_add(JNIEnv* env, jobject thiz, jlong target, jstring collections, jstring addresses, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* collections_c = str(&a, collections);
    const char* addresses_c = str(&a, addresses);
    if (abandoned(&a)) return NULL;
    DefraReplicatorAddOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_p2p_replicator_add(H(target), collections_c, addresses_c, opts));
    args_free(&a);
    return res;
}

static jobject p2p_replicator_delete(JNIEnv* env, jobject thiz, jlong target, jstring collections, jstring id, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* id_c = str(&a, id);
    DefraReplicatorDeleteOptions opts = { .identity = H(identity), .collections = str(&a, collections) };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_p2p_replicator_delete(H(target), id_c, opts));
    args_free(&a);
    return res;
}

// The sync calls take their timeout in milliseconds, 0 for none; the Java
// side converts its Duration (or a deprecated Go duration string) first.

static jobject p2p_sync_branchable(JNIEnv* env, jobject thiz, jlong target, jstring collection_id, jlong timeout_ms, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* id_c = str(&a, collection_id);
    if (abandoned(&a)) return NULL;
    DefraP2PSyncOptions opts = { .identity = H(identity), .timeout_ms = (int64_t)timeout_ms };
    jobject res = result(env, defra_p2p_collection_sync_branchable(H(target), id_c, opts));
    args_free(&a);
    return res;
}

static jobject p2p_sync_versions(JNIEnv* env, jobject thiz, jlong target, jstring version_ids, jlong timeout_ms, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* ids_c = str(&a, version_ids);
    if (abandoned(&a)) return NULL;
    DefraP2PSyncOptions opts = { .identity = H(identity), .timeout_ms = (int64_t)timeout_ms };
    jobject res = result(env, defra_p2p_collection_sync_versions(H(target), ids_c, opts));
    args_free(&a);
    return res;
}

static jobject p2p_document_sync(JNIEnv* env, jobject thiz, jlong target, jstring collection_name, jstring doc_ids, jlong timeout_ms, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* collection_c = str(&a, collection_name);
    const char* ids_c = str(&a, doc_ids);
    if (abandoned(&a)) return NULL;
    DefraP2PSyncOptions opts = { .identity = H(identity), .timeout_ms = (int64_t)timeout_ms };
    jobject res = result(env, defra_p2p_document_sync(H(target), collection_c, ids_c, opts));
    args_free(&a);
    return res;
}

//=============================================================================
// Queries, subscriptions and transactions
//=============================================================================

static jobject query_execute(JNIEnv* env, jobject thiz, jlong target, jstring query, jlong identity, jstring operation_name, jstring variables) {
    UNUSED(thiz);
    ARGS;
    const char* query_c = str(&a, query);
    DefraQueryOptions opts = {
        .identity = H(identity),
        .operation_name = str(&a, operation_name),
        .variables = str(&a, variables),
    };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_query_execute(H(target), query_c, opts));
    args_free(&a);
    return res;
}

static jobject subscription_poll(JNIEnv* env, jobject thiz, jstring id) {
    UNUSED(thiz);
    ARGS;
    const char* id_c = str(&a, id);
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_subscription_poll(id_c));
    args_free(&a);
    return res;
}

static jobject subscription_close(JNIEnv* env, jobject thiz, jstring id) {
    UNUSED(thiz);
    ARGS;
    const char* id_c = str(&a, id);
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_subscription_close(id_c));
    args_free(&a);
    return res;
}

static jobject transaction_create(JNIEnv* env, jobject thiz, jlong node, jboolean read_only) {
    UNUSED(thiz);
    DefraTransactionOptions opts = { .read_only = B(read_only) };
    return txn_result(env, defra_transaction_create(H(node), opts));
}

static jobject transaction_commit(JNIEnv* env, jobject thiz, jlong txn) {
    UNUSED(thiz);
    return result(env, defra_transaction_commit(H(txn)));
}

static void transaction_discard(JNIEnv* env, jobject thiz, jlong txn) {
    UNUSED(env);
    UNUSED(thiz);
    defra_transaction_discard(H(txn));
}

//=============================================================================
// Calls added with the Rust C API
//=============================================================================

static jobject acp_nac_purge(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraNacOptions opts = { .identity = H(identity) };
    return result(env, defra_acp_nac_purge(H(target), opts));
}

static jobject batch_start(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraBatchOptions opts = { .identity = H(identity) };
    return result(env, defra_batch_start(H(target), opts));
}

static jobject batch_sign(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    DefraBatchOptions opts = { .identity = H(identity) };
    return result(env, defra_batch_sign(H(target), opts));
}

static jobject document_save_many(JNIEnv* env, jobject thiz, jlong target, jstring json, jboolean encrypt, jstring encrypted_fields, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* json_c = str(&a, json);
    const char* fields_c = str(&a, encrypted_fields);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraDocumentAddOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
        .encrypt = B(encrypt),
        .encrypted_fields = fields_c,
        .signing = c.signing,
    };
    jobject res = result(env, defra_document_save_many(H(target), json_c, opts));
    args_free(&a);
    return res;
}

static jobject document_purge(JNIEnv* env, jobject thiz, jlong target, jstring doc_ids_json, jobject options, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* ids_c = str(&a, doc_ids_json);
    Collection c = collection(&a, options);
    if (abandoned(&a)) return NULL;
    DefraDocumentPurgeOptions opts = {
        .identity = H(identity),
        .collection_name = c.name,
        .collection_version_id = c.version_id,
        .collection_id = c.collection_id,
        .collection_get_inactive = c.get_inactive,
    };
    jobject res = result(env, defra_document_purge(H(target), ids_c, opts));
    args_free(&a);
    return res;
}

// The query's strings are read before defra_query_execute_async returns, so
// they are freed at once; its result reaches on_query_result later.
static void query_execute_async(JNIEnv* env, jobject thiz, jlong target, jstring query, jlong identity, jstring operation_name, jstring variables, jlong handle) {
    UNUSED(thiz);
    ARGS;
    const char* query_c = str(&a, query);
    DefraQueryOptions opts = {
        .identity = H(identity),
        .operation_name = str(&a, operation_name),
        .variables = str(&a, variables),
    };
    // A failed conversion leaves its exception for executeQueryAsync, which
    // fails the future with it.
    if (abandoned(&a)) return;
    defra_query_execute_async(H(target), query_c, on_query_result, H(handle), opts);
    args_free(&a);
}

static jobject event_subscribe(JNIEnv* env, jobject thiz, jlong target, jstring names, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* names_c = str(&a, names);
    if (abandoned(&a)) return NULL;
    DefraEventSubscribeOptions opts = { .identity = H(identity) };
    jobject res = result(env, defra_event_subscribe(H(target), names_c, opts));
    args_free(&a);
    return res;
}

static jobject identity_did(JNIEnv* env, jobject thiz, jlong identity) {
    UNUSED(thiz);
    return result(env, defra_identity_did(H(identity)));
}

static jobject identity_from_bearer_token(JNIEnv* env, jobject thiz, jstring did, jstring bearer_token) {
    UNUSED(thiz);
    ARGS;
    const char* did_c = str(&a, did);
    const char* token_c = str(&a, bearer_token);
    if (abandoned(&a)) return NULL;
    DefraIdentityBearerOptions opts;
    memset(&opts, 0, sizeof opts);
    jobject res = identity_result(env, defra_identity_new_from_bearer_token(did_c, token_c, opts));
    args_free(&a);
    return res;
}

// A remote identity signs through on_sign, which starts each signature on
// the Java signer the handle names and returns at once: no DefraDB thread
// waits while Java signs.
static jobject identity_remote(JNIEnv* env, jobject thiz, jstring did, jstring key_type, jstring public_key_hex, jlong signer) {
    UNUSED(thiz);
    ARGS;
    const char* did_c = str(&a, did);
    DefraRemoteIdentityOptions opts;
    memset(&opts, 0, sizeof opts);
    opts.key_type = str(&a, key_type);
    opts.public_key_hex = str(&a, public_key_hex);
    opts.signer_handle = H(signer);
    opts.sign_async = on_sign;
    if (abandoned(&a)) return NULL;
    jobject res = identity_result(env, defra_identity_new_remote(did_c, opts));
    args_free(&a);
    return res;
}

static jobject node_set_identity(JNIEnv* env, jobject thiz, jlong node, jlong identity) {
    UNUSED(thiz);
    return result(env, defra_node_set_identity(H(node), H(identity)));
}

static struct DefraResult call_replicator_retry(uintptr_t t, DefraP2POptions o) { return defra_p2p_replicator_retry(t, o); }
static struct DefraResult call_shareable_address(uintptr_t t, DefraP2POptions o) { return defra_mobile_shareable_address(t, o); }
static struct DefraResult call_sync_status(uintptr_t t, DefraP2POptions o) { return defra_mobile_sync_status(t, o); }
static struct DefraResult call_notify_network_change(uintptr_t t, DefraP2POptions o) { return defra_mobile_notify_network_change(t, o); }
static struct DefraResult call_peer_details(uintptr_t t, const char* n, DefraP2POptions o) { return defra_mobile_peer_details(t, n, o); }

static jobject p2p_replicator_retry(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_replicator_retry);
}

static jobject p2p_replicator_add_filtered(JNIEnv* env, jobject thiz, jlong target, jstring collections, jstring addresses, jstring filters_json, jlong identity) {
    UNUSED(thiz);
    ARGS;
    const char* collections_c = str(&a, collections);
    const char* addresses_c = str(&a, addresses);
    DefraReplicatorAddOptions opts = { .identity = H(identity), .filters_json = str(&a, filters_json) };
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_p2p_replicator_add(H(target), collections_c, addresses_c, opts));
    args_free(&a);
    return res;
}

static jobject mobile_shareable_address(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_shareable_address);
}

static jobject mobile_sync_status(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_sync_status);
}

static jobject mobile_notify_network_change(JNIEnv* env, jobject thiz, jlong target, jlong identity) {
    UNUSED(thiz);
    return p2p_call(env, target, identity, call_notify_network_change);
}

static jobject mobile_peer_details(JNIEnv* env, jobject thiz, jlong target, jstring peer_id, jlong identity) {
    UNUSED(thiz);
    return p2p_names_call(env, target, peer_id, identity, call_peer_details);
}

static jobject profiling_start(JNIEnv* env, jclass cls, jstring output_path) {
    UNUSED(cls);
    if (defra_profiling_start == NULL) {
        return refused(env, "profiling is not available: build DefraDB with its profiling feature");
    }
    ARGS;
    const char* path_c = str(&a, output_path);
    if (abandoned(&a)) return NULL;
    jobject res = result(env, defra_profiling_start(path_c));
    args_free(&a);
    return res;
}

static jobject profiling_stop(JNIEnv* env, jclass cls) {
    UNUSED(cls);
    if (defra_profiling_stop == NULL) {
        return refused(env, "profiling is not available: build DefraDB with its profiling feature");
    }
    return result(env, defra_profiling_stop());
}

//=============================================================================
// Registration
//=============================================================================

#define S "Ljava/lang/String;"
#define R "Lsource/defra/DefraResult;"
#define CO "Lsource/defra/DefraCollectionOptions;"
#define M(name, sig, fn) { (char*)(name), (char*)(sig), (void*)(fn) }

// The natives DefraNode, DefraTransaction and DefraCollection share, on a
// node, transaction or collection store handle.
#define STORE_NATIVES \
    M("AddDocumentNative", "(J" S "Z" S CO "J)" R, document_add), \
    M("DeleteDocumentNative", "(J" S S CO "J)" R, document_delete), \
    M("GetDocumentNative", "(J" S "Z" CO "J)" R, document_get), \
    M("UpdateDocumentNative", "(J" S S S CO "J)" R, document_update), \
    M("DeleteEncryptedIndexNative", "(J" S S "J)" R, encrypted_index_delete), \
    M("ListEncryptedIndexesNative", "(J" S "J)" R, encrypted_index_list), \
    M("NewEncryptedIndexNative", "(J" S S S "J)" R, encrypted_index_new), \
    M("DeleteIndexNative", "(J" S CO "J)" R, index_delete), \
    M("ListIndexesNative", "(J" CO "J)" R, index_list), \
    M("NewIndexNative", "(J" S S "Z" S CO "J)" R, index_new), \
    M("SaveDocumentsNative", "(J" S "Z" S CO "J)" R, document_save_many), \
    M("PurgeDocumentsNative", "(J" S CO "J)" R, document_purge)

// The natives DefraNode and DefraTransaction share beyond those.
#define TARGET_NATIVES \
    M("ACPAddDACPolicyNative", "(JJ" S ")" R, acp_dac_policy_add), \
    M("ACPAddDACActorRelationshipNative", "(JJ" S S S S ")" R, acp_dac_relationship_add), \
    M("ACPDeleteDACActorRelationshipNative", "(JJ" S S S S ")" R, acp_dac_relationship_delete), \
    M("ACPDisableNACNative", "(JJ)" R, acp_nac_disable), \
    M("ACPReEnableNACNative", "(JJ)" R, acp_nac_re_enable), \
    M("ACPAddNACActorRelationshipNative", "(JJ" S S ")" R, acp_nac_relationship_add), \
    M("ACPDeleteNACActorRelationshipNative", "(JJ" S S ")" R, acp_nac_relationship_delete), \
    M("ACPGetNACStatusNative", "(JJ)" R, acp_nac_status), \
    M("BasicExportNative", "(J" S S S "Z)" R, backup_export), \
    M("BasicImportNative", "(J" S ")" R, backup_import), \
    M("VerifyBlockSignatureNative", "(J" S S S "J)" R, block_verify), \
    M("AddCollectionNative", "(J" S "J)" R, collection_add), \
    M("DescribeCollectionNative", "(J" CO "J)" R, collection_describe), \
    M("PatchCollectionNative", "(J" S S "J)" R, collection_patch), \
    M("SetActiveCollectionNative", "(J" CO "J)" R, collection_set_active), \
    M("TruncateCollectionNative", "(J" S CO "J)" R, collection_truncate), \
    M("PrintDumpNative", "(J)" R, print_dump), \
    M("GetNodeIdentityNative", "(J)" R, node_identity), \
    M("AddLensNative", "(JJ" S ")" R, lens_add), \
    M("ListLensesNative", "(JJ)" R, lens_list), \
    M("SetLensNative", "(JJ" S S S ")" R, lens_set), \
    M("ExecuteQueryNative", "(J" S "J" S S ")" R, query_execute), \
    M("AddViewNative", "(J" S S S "J)" R, view_add), \
    M("RefreshViewNative", "(J" CO "J)" R, view_refresh), \
    M("BatchStartNative", "(JJ)" R, batch_start), \
    M("BatchSignNative", "(JJ)" R, batch_sign), \
    M("ExecuteQueryAsyncNative", "(J" S "J" S S "J)V", query_execute_async)

static const JNINativeMethod node_natives[] = {
    STORE_NATIVES,
    TARGET_NATIVES,
    M("ListActionsNative", "(JJ)" R, action_list),
    M("DeleteCollectionNative", "(J" S "IJ)" R, collection_delete),
    M("ExportIdentityPrivateKeyNative", "(J)" R, identity_export_private_key),
    M("FreeIdentityNative", "(J)V", identity_free),
    M("IdentityNewNative", "(" S ")Lsource/defra/DefraIdentityResult;", identity_new),
    M("NewIdentityFromPrivateKeyNative", "(" S ")Lsource/defra/DefraIdentityResult;", identity_new_from_private_key),
    M("NodeCloseNative", "(J)" R, node_close),
    M("NewNodeNative", "(Lsource/defra/DefraNodeInitOptions;J)Lsource/defra/DefraNewNodeResult;", node_new),
    M("GetNodeOptionsNative", "(J)" R, node_options),
    M("AddP2PCollectionNative", "(J" S "J)" R, p2p_collection_add),
    M("DeleteP2PCollectionNative", "(J" S "J)" R, p2p_collection_delete),
    M("ListP2PCollectionsNative", "(JJ)" R, p2p_collection_list),
    M("SyncP2PBranchableCollectionNative", "(J" S "JJ)" R, p2p_sync_branchable),
    M("SyncP2PCollectionVersionsNative", "(J" S "JJ)" R, p2p_sync_versions),
    M("ConnectP2PPeersNative", "(J" S "J)" R, p2p_connect),
    M("DisconnectP2PPeersNative", "(J" S "J)" R, p2p_disconnect),
    M("AddP2PDocumentNative", "(J" S "J)" R, p2p_document_add),
    M("DeleteP2PDocumentNative", "(J" S "J)" R, p2p_document_delete),
    M("ListP2PDocumentsNative", "(JJ)" R, p2p_document_list),
    M("SyncP2PDocumentsNative", "(J" S S "JJ)" R, p2p_document_sync),
    M("GetP2PInfoNative", "(JJ)" R, p2p_info),
    M("ListP2PActivePeersNative", "(JJ)" R, p2p_peer_list),
    M("AddP2PReplicatorNative", "(J" S S "J)" R, p2p_replicator_add),
    M("DeleteP2PReplicatorNative", "(J" S S "J)" R, p2p_replicator_delete),
    M("ListP2PReplicatorsNative", "(JJ)" R, p2p_replicator_list),
    M("CloseSubscriptionNative", "(" S ")" R, subscription_close),
    M("PollSubscriptionNative", "(" S ")" R, subscription_poll),
    M("TransactionCreateNative", "(JZ)Lsource/defra/DefraTransactionResult;", transaction_create),
    M("GetVersionNative", "(ZZ)" R, version_get),
    M("ACPPurgeNACNative", "(JJ)" R, acp_nac_purge),
    M("SubscribeEventsNative", "(J" S "J)" R, event_subscribe),
    M("IdentityDIDNative", "(J)" R, identity_did),
    M("IdentityFromBearerTokenNative", "(" S S ")Lsource/defra/DefraIdentityResult;", identity_from_bearer_token),
    M("IdentityRemoteNative", "(" S S S "J)Lsource/defra/DefraIdentityResult;", identity_remote),
    M("SetNodeIdentityNative", "(JJ)" R, node_set_identity),
    M("RetryP2PReplicatorsNative", "(JJ)" R, p2p_replicator_retry),
    M("AddP2PReplicatorFilteredNative", "(J" S S S "J)" R, p2p_replicator_add_filtered),
    M("ShareableAddressNative", "(JJ)" R, mobile_shareable_address),
    M("SyncStatusNative", "(JJ)" R, mobile_sync_status),
    M("NotifyNetworkChangeNative", "(JJ)" R, mobile_notify_network_change),
    M("PeerDetailsNative", "(J" S "J)" R, mobile_peer_details),
    M("ProfilingStartNative", "(" S ")" R, profiling_start),
    M("ProfilingStopNative", "()" R, profiling_stop),
};

static const JNINativeMethod callbacks_natives[] = {
    M("CompleteSignNative", "(JI[B)I", sign_complete),
};

static const JNINativeMethod transaction_natives[] = {
    STORE_NATIVES,
    TARGET_NATIVES,
    M("TransactionCommitNative", "(J)" R, transaction_commit),
    M("TransactionDiscardNative", "(J)V", transaction_discard),
};

static const JNINativeMethod collection_natives[] = {
    STORE_NATIVES,
};

#define COUNT(array) ((jint)(sizeof(array) / sizeof((array)[0])))

static int register_natives(JNIEnv* env, const char* class_name, const JNINativeMethod* methods, jint count) {
    jclass cls = (*env)->FindClass(env, class_name);
    if (cls == NULL) {
        return -1;
    }
    int registered = (*env)->RegisterNatives(env, cls, methods, count);
    (*env)->DeleteLocalRef(env, cls);
    return registered == 0 ? 0 : -1;
}

// A global reference to `class_name`, and its constructor `ctor_sig`.
static int resolve(JNIEnv* env, const char* class_name, const char* ctor_sig, jclass* cls, jmethodID* ctor) {
    jclass local = (*env)->FindClass(env, class_name);
    if (local == NULL) {
        return -1;
    }
    *cls = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    *ctor = (*env)->GetMethodID(env, *cls, "<init>", ctor_sig);
    return *cls != NULL && *ctor != NULL ? 0 : -1;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    UNUSED(reserved);
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jvm = vm;
    if (pthread_key_create(&detach_key, detach_thread) != 0) {
        return JNI_ERR;
    }
#ifdef __ANDROID__
    // DefraDB's network runtime, nuclei, probes for io_uring at startup by
    // calling io_uring_setup in a cloned child. Android's app seccomp filter
    // answers that call with SIGSYS, which Android reports as a crash of the
    // app and which ends it, and an app may not use io_uring anyway. Naming
    // epoll skips the probe; an app that set the variable keeps its value.
    setenv("NUCLEI_BACKEND", "epoll", 0);
#endif
    // DefraDB's threads find no application classes through FindClass, so
    // the class they call back into is resolved here, once.
    jclass callbacks = (*env)->FindClass(env, "source/defra/NativeCallbacks");
    if (callbacks == NULL) {
        return JNI_ERR;
    }
    callbacks_class = (jclass)(*env)->NewGlobalRef(env, callbacks);
    (*env)->DeleteLocalRef(env, callbacks);
    callbacks_deliver_query = (*env)->GetStaticMethodID(env, callbacks_class, "deliverQuery", "(JI" S S ")V");
    callbacks_sign = (*env)->GetStaticMethodID(env, callbacks_class, "sign", "(J" S S "[BJ)I");
    callbacks_keep = (*env)->GetStaticMethodID(env, callbacks_class, "keep", "(J" S S S S ")Z");
    if (callbacks_class == NULL || callbacks_deliver_query == NULL || callbacks_sign == NULL || callbacks_keep == NULL) {
        return JNI_ERR;
    }
    if (resolve(env, "source/defra/DefraResult", "(I" S S ")V", &result_class, &result_ctor) != 0
        || resolve(env, "source/defra/DefraNewNodeResult", "(I" S "J)V", &node_result_class, &node_result_ctor) != 0
        || resolve(env, "source/defra/DefraIdentityResult", "(I" S "J)V", &identity_result_class, &identity_result_ctor) != 0
        || resolve(env, "source/defra/DefraTransactionResult", "(I" S "J)V", &txn_result_class, &txn_result_ctor) != 0
        || resolve(env, "java/lang/String", "([B" S ")V", &string_class, &string_from_bytes) != 0) {
        return JNI_ERR;
    }
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    if (charset == NULL) {
        return JNI_ERR;
    }
    utf8_charset = (jstring)(*env)->NewGlobalRef(env, charset);
    (*env)->DeleteLocalRef(env, charset);

    if (register_natives(env, "source/defra/DefraNode", node_natives, COUNT(node_natives)) != 0
        || register_natives(env, "source/defra/DefraTransaction", transaction_natives, COUNT(transaction_natives)) != 0
        || register_natives(env, "source/defra/DefraCollection", collection_natives, COUNT(collection_natives)) != 0
        || register_natives(env, "source/defra/NativeCallbacks", callbacks_natives, COUNT(callbacks_natives)) != 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
