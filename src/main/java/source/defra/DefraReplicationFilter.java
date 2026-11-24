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
 * Decides whether a node keeps a document a peer replicates to it, before
 * anything of it is stored. Set it as
 * {@link DefraNodeInitOptions#replicationFilter}.
 *
 * <p>DefraDB calls it on its database and network threads, from several at
 * once: it must be thread-safe, return promptly, and not call back into
 * DefraDB. It may be asked about one document more than once (on arrival, on
 * merge, on a merge retry), so it decides from its arguments alone. An
 * exception refuses the document.</p>
 */
public interface DefraReplicationFilter {

    /**
     * Whether to keep a replicated document.
     *
     * @param collectionID the collection's ID, the same across renames
     * @param collectionName the collection's name
     * @param docID the document's ID
     * @param fieldsJSON the values the document's commit writes, as a JSON
     *     object; an integer is a JSON integer, a float always has a fraction
     *     or exponent, bytes are base64, and an encrypted or missing field is
     *     absent
     * @return true to keep the document, false to refuse it
     */
    boolean keep(String collectionID, String collectionName, String docID, String fieldsJSON);
}
