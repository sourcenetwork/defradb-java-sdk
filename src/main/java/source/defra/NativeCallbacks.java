// Copyright 2026 Democratized Data Foundation
//
// Use of this software is governed by the Business Source License
// included in the file licenses/BSL.txt.
//
// As of the Change Date specified in that file, in accordance with
// the Business Source License, use of this software will be governed
// by the Apache License, Version 2.0, included in the file
// licenses/APL.txt.

package source.defra;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * What DefraDB calls back into: pending asynchronous queries, remote signers
 * and replication filters, each under a numeric handle the native layer
 * passes back. DefraDB calls these methods on its own threads, which must
 * not wait on application code, so anything slow (completing a future,
 * signing) moves to this class's threads first.
 */
final class NativeCallbacks {

    /** Signing status for a request that did not reach the signer; DefraDB may ask again. */
    static final int SIGN_RETRYABLE = 2;

    /** The error of a query result the native layer could not hand to Java. */
    static final String UNDELIVERABLE = "DefraDB's result could not be converted for Java";

    private static final AtomicLong NEXT = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, CompletableFuture<DefraResult>> QUERIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, DefraRemoteSigner> SIGNERS = new ConcurrentHashMap<>();
    // By identity, not equals: a signer is one registration however it
    // defines equality, and an equal but distinct instance is another.
    private static final Map<DefraRemoteSigner, Long> SIGNER_HANDLES = new IdentityHashMap<>();
    private static final ConcurrentHashMap<Long, DefraReplicationFilter> FILTERS = new ConcurrentHashMap<>();
    // Unbounded on purpose: a signer may block (on a user prompt, a remote
    // keystore), and a bounded pool would queue every other signature and
    // query result behind it.
    private static final ExecutorService WORK = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "defra-callbacks");
        thread.setDaemon(true);
        return thread;
    });

    private NativeCallbacks() {
    }

    private static native int CompleteSignNative(long completion, int status, byte[] signature);

    /**
     * Runs an asynchronous query: `start` makes the native call with the
     * handle its result is delivered under. The future it returns completes
     * with the response, or exceptionally with a {@link DefraException}, or
     * with whatever the native call threw before reaching DefraDB.
     */
    static CompletableFuture<String> query(LongConsumer start) {
        CompletableFuture<DefraResult> raw = new CompletableFuture<>();
        long handle = NEXT.getAndIncrement();
        QUERIES.put(handle, raw);
        try {
            start.accept(handle);
        } catch (Throwable t) {
            QUERIES.remove(handle);
            raw.completeExceptionally(t);
        }
        return values(raw);
    }

    /**
     * The value of each result `raw` completes with: the response, or a
     * subscription's ID (status 2); a failed call completes exceptionally.
     */
    private static CompletableFuture<String> values(CompletableFuture<DefraResult> raw) {
        CompletableFuture<String> values = new CompletableFuture<>();
        raw.whenComplete((result, failure) -> {
            if (failure != null) {
                values.completeExceptionally(failure);
            } else if (result.status == 1) {
                values.completeExceptionally(new DefraException(result.error));
            } else {
                values.complete(result.value);
            }
        });
        return values;
    }

    /**
     * `signer`'s handle, the same for every registration of that instance:
     * DefraDB fixes a DID's signer while it has live identities, and
     * recognizes it by this value. A signer stays registered for the life of
     * the process, since nodes keep signing through it after its identities
     * are freed.
     */
    static long signer(DefraRemoteSigner signer) {
        synchronized (SIGNER_HANDLES) {
            Long handle = SIGNER_HANDLES.get(signer);
            if (handle == null) {
                handle = NEXT.getAndIncrement();
                SIGNERS.put(handle, signer);
                SIGNER_HANDLES.put(signer, handle);
            }
            return handle;
        }
    }

    /** A handle for `filter`, kept until {@link #releaseFilter} after its node closes. */
    static long filter(DefraReplicationFilter filter) {
        long handle = NEXT.getAndIncrement();
        FILTERS.put(handle, filter);
        return handle;
    }

    static void releaseFilter(long handle) {
        FILTERS.remove(handle);
    }

    // Called from native code.

    static void deliverQuery(long handle, int status, String error, String value) {
        CompletableFuture<DefraResult> future = QUERIES.remove(handle);
        if (future != null) {
            DefraResult result = new DefraResult(status, status == 1 && error == null ? UNDELIVERABLE : error, value);
            WORK.execute(() -> future.complete(result));
        }
    }

    static int sign(long handle, String did, String keyType, byte[] payload, long completion) {
        DefraRemoteSigner signer = SIGNERS.get(handle);
        if (signer == null) {
            return 1;
        }
        WORK.execute(() -> {
            byte[] signature = null;
            int status;
            try {
                signature = signer.sign(did, keyType, payload);
                status = signature == null ? 1 : 0;
            } catch (DefraSignRetryableException e) {
                status = SIGN_RETRYABLE;
            } catch (Throwable t) {
                status = 1;
            }
            CompleteSignNative(completion, status, signature);
        });
        return 0;
    }

    /**
     * Whether the filter `handle` names keeps a document. A filter released
     * with its node, or one that throws, refuses: a filter fails closed.
     */
    static boolean keep(long handle, String collectionID, String collectionName, String docID, String fieldsJSON) {
        DefraReplicationFilter filter = FILTERS.get(handle);
        if (filter == null) {
            return false;
        }
        try {
            return filter.keep(collectionID, collectionName, docID, fieldsJSON);
        } catch (Throwable t) {
            return false;
        }
    }
}
