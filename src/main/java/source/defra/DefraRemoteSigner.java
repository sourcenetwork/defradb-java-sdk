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
 * Signs for an identity whose private key stays outside DefraDB: a
 * device-bound key (an Android Keystore or Secure Enclave key) or a remote
 * identity provider.
 *
 * <p>DefraDB asks for signatures on its own schedule and never waits on the
 * thread that asked: {@link #sign} runs on an SDK thread, so it may block,
 * for a user prompt or a network round trip. One signer may be asked for
 * several signatures at once.</p>
 *
 * @see DefraNode#identityRemote(String, String, String, DefraRemoteSigner)
 */
public interface DefraRemoteSigner {

    /**
     * Signs {@code payload}, the exact bytes to sign.
     *
     * <p>An ECDSA key ({@code secp256r1}, {@code secp256k1}) answers an ASN.1
     * DER (X9.62) signature, not raw {@code r || s}.</p>
     *
     * @param did the identity's DID
     * @param keyType the identity's key type, for example {@code secp256r1}
     * @param payload the bytes to sign
     * @return the signature; {@code null} refuses
     * @throws DefraSignRetryableException when the request did not reach the
     *     signer or its answer was lost, so DefraDB may ask again
     * @throws Exception when the signer refuses
     */
    byte[] sign(String did, String keyType, byte[] payload) throws Exception;
}
