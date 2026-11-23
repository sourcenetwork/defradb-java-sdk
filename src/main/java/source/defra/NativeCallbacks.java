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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final AtomicLong NEXT = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, CompletableFuture<DefraResult>> QUERIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, DefraRemoteSigner> SIGNERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<DefraRemoteSigner, Long> SIGNER_HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, DefraReplicationFilter> FILTERS = new ConcurrentHashMap<>();
    private static final ExecutorService WORK = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "defra-callbacks");
        thread.setDaemon(true);
        return thread;
    });

    private NativeCallbacks() {
    }

    private static native int CompleteSignNative(long completion, int status, byte[] signature);

    /** A handle for a query whose result `future` receives. */
    static long pendingQuery(CompletableFuture<DefraResult> future) {
        long handle = NEXT.getAndIncrement();
        QUERIES.put(handle, future);
        return handle;
    }

    /**
     * The value of each result `raw` completes with: the response, or a
     * subscription's ID (status 2); a failed call completes exceptionally.
     */
    static CompletableFuture<String> values(CompletableFuture<DefraResult> raw) {
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
     * `signer`'s handle, the same for every registration of it: DefraDB
     * fixes a DID's signer while it has live identities, and recognizes it by
     * this value. A signer stays registered for the life of the process,
     * since nodes keep signing through it after its identities are freed.
     */
    static long signer(DefraRemoteSigner signer) {
        return SIGNER_HANDLES.computeIfAbsent(signer, s -> {
            long handle = NEXT.getAndIncrement();
            SIGNERS.put(handle, s);
            return handle;
        });
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
            DefraResult result = new DefraResult(status, error, value);
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

    static boolean keep(long handle, String collectionID, String collectionName, String docID, String fieldsJSON) {
        DefraReplicationFilter filter = FILTERS.get(handle);
        if (filter == null) {
            return true;
        }
        try {
            return filter.keep(collectionID, collectionName, docID, fieldsJSON);
        } catch (Throwable t) {
            return false;
        }
    }
}
