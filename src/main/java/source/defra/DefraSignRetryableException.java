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

/**
 * Thrown by a {@link DefraRemoteSigner} when a signature request did not
 * reach the signer, or its answer did not come back. Signing the same bytes
 * again is safe, so DefraDB may ask again; any other failure refuses the
 * signature.
 */
public class DefraSignRetryableException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the request did not complete
     */
    public DefraSignRetryableException(String message) {
        super(message);
    }
}
