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

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/**
 * An explicit DefraDB transaction.
 *
 * <p>Operations remain isolated until {@link #commit()} is called. Call
 * {@link #discard()} to abandon the transaction. Overloads accepting a
 * {@link DefraIdentity} execute as that identity.</p>
 */
public class DefraTransaction {

    static {
        NativeLoader.load("nativewrapper");
        NativeLoader.load("defradb");
    }

    // ACP Methods
    private native DefraResult ACPAddDACPolicyNative(long txnPtr, long identityPtr, String policy);
    private native DefraResult ACPAddDACActorRelationshipNative(long txnPtr, long identityPtr, String collection, String docID, String relation, String actor);
    private native DefraResult ACPDeleteDACActorRelationshipNative(long txnPtr, long identityPtr, String collection, String docID, String relation, String actor);
    private native DefraResult ACPDisableNACNative(long txnPtr, long identityPtr);
    private native DefraResult ACPReEnableNACNative(long txnPtr, long identityPtr);
    private native DefraResult ACPAddNACActorRelationshipNative(long txnPtr, long identityPtr, String relation, String actor);
    private native DefraResult ACPDeleteNACActorRelationshipNative(long txnPtr, long identityPtr, String relation, String actor);
    private native DefraResult ACPGetNACStatusNative(long txnPtr, long identityPtr);

    // Backup Methods
    private native DefraResult BasicExportNative(long txnPtr, String filepath, String collections, String format, boolean pretty);
    private native DefraResult BasicImportNative(long txnPtr, String filepath);

    // Block Verification
    private native DefraResult VerifyBlockSignatureNative(long txnPtr, String keyType, String publicKey, String cid, long identityPtr);

    // Collection Methods
    private native DefraResult AddCollectionNative(long txnPtr, String sdl, long identityPtr);
    private native DefraResult DescribeCollectionNative(long txnPtr, DefraCollectionOptions options, long identityPtr);
    private native DefraResult PatchCollectionNative(long txnPtr, String patch, String lensConfig, long identityPtr);
    private native DefraResult SetActiveCollectionNative(long txnPtr, DefraCollectionOptions options, long identityPtr);
    private native DefraResult TruncateCollectionNative(long txnPtr, String filterJSON, DefraCollectionOptions options, long identityPtr);

    // Debug Methods
    private native DefraResult PrintDumpNative(long txnPtr);

    // Document Methods
    private native DefraResult AddDocumentNative(long txnPtr, String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options, long identityPtr);
    private native DefraResult DeleteDocumentNative(long txnPtr, String docID, String filter, DefraCollectionOptions options, long identityPtr);
    private native DefraResult GetDocumentNative(long txnPtr, String docID, boolean showDeleted, DefraCollectionOptions options, long identityPtr);
    private native DefraResult UpdateDocumentNative(long txnPtr, String docID, String filter, String updater, DefraCollectionOptions options, long identityPtr);

    // Encrypted Index Methods
    private native DefraResult DeleteEncryptedIndexNative(long txnPtr, String collectionName, String fieldName, long identityPtr);
    private native DefraResult ListEncryptedIndexesNative(long txnPtr, String collectionName, long identityPtr);
    private native DefraResult NewEncryptedIndexNative(long txnPtr, String collectionName, String fieldName, String indexType, long identityPtr);

    // Identity Methods
    private native DefraResult GetNodeIdentityNative(long txnPtr);

    // Index Methods
    private native DefraResult DeleteIndexNative(long txnPtr, String indexName, DefraCollectionOptions options, long identityPtr);
    private native DefraResult ListIndexesNative(long txnPtr, DefraCollectionOptions options, long identityPtr);
    private native DefraResult NewIndexNative(long txnPtr, String indexName, String fields, boolean isUnique, String vectorJSON, DefraCollectionOptions options, long identityPtr);

    // Lens Methods
    private native DefraResult AddLensNative(long txnPtr, long identityPtr, String cfg);
    private native DefraResult ListLensesNative(long txnPtr, long identityPtr);
    private native DefraResult SetLensNative(long txnPtr, long identityPtr, String src, String dst, String cfg);

    // Query Methods
    private native DefraResult ExecuteQueryNative(long txnPtr, String query, long identityPtr, String operationName, String variables);

    // Transaction Methods
    private native DefraResult TransactionCommitNative(long txnPtr);
    private native void TransactionDiscardNative(long txnPtr);

    // View Methods
    private native DefraResult AddViewNative(long txnPtr, String query, String sdl, String transformCID, long identityPtr);
    private native DefraResult RefreshViewNative(long txnPtr, DefraCollectionOptions options, long identityPtr);

    // Added with the Rust C API
    private native DefraResult BatchStartNative(long txnPtr, long identityPtr);
    private native DefraResult BatchSignNative(long txnPtr, long identityPtr);
    private native DefraResult SaveDocumentsNative(long txnPtr, String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options, long identityPtr);
    private native DefraResult PurgeDocumentsNative(long txnPtr, String docIDsJSON, DefraCollectionOptions options, long identityPtr);
    private native void ExecuteQueryAsyncNative(long txnPtr, String query, long identityPtr, String operationName, String variables, long callbackHandle);

    private long txnPtr;

    /**
     * Wraps a native transaction pointer.
     *
     * @param ptr native transaction pointer
     */
    public DefraTransaction(long ptr) {
        this.txnPtr = ptr;
    }

    /**
     * Commits the transaction.
     *
     * @return the native commit result encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String commit() throws DefraException {
        DefraResult result = TransactionCommitNative(this.txnPtr);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Discards the transaction and releases its native resources.
     */
    public void discard() {
        TransactionDiscardNative(this.txnPtr);
    }

    // ACP Methods
    /**
     * Adds a document access-control policy within this transaction.
     *
     * @param policy policy definition encoded as JSON
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPAddDACPolicy(String policy) throws DefraException {
        DefraResult result = ACPAddDACPolicyNative(this.txnPtr, 0, policy);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds a document access-control policy within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param policy policy definition encoded as JSON
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPAddDACPolicy(String policy, DefraIdentity identity) throws DefraException {
        DefraResult result = ACPAddDACPolicyNative(this.txnPtr, identity.getPointer(), policy);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds an actor relationship to a document access-control policy within this transaction.
     *
     * @param collection collection name or collection selector encoded as JSON
     * @param docID document identifier
     * @param relation relationship name
     * @param actor actor DID
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPAddDACActorRelationship(String collection, String docID, String relation, String actor) throws DefraException {
        DefraResult result = ACPAddDACActorRelationshipNative(this.txnPtr, 0, collection, docID, relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds an actor relationship to a document access-control policy within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param collection collection name or collection selector encoded as JSON
     * @param docID document identifier
     * @param relation relationship name
     * @param actor actor DID
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPAddDACActorRelationship(String collection, String docID, String relation, String actor, DefraIdentity identity) throws DefraException {
        DefraResult result = ACPAddDACActorRelationshipNative(this.txnPtr, identity.getPointer(), collection, docID, relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an actor relationship from a document access-control policy within this transaction.
     *
     * @param collection collection name or collection selector encoded as JSON
     * @param docID document identifier
     * @param relation relationship name
     * @param actor actor DID
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPDeleteDACActorRelationship(String collection, String docID, String relation, String actor) throws DefraException {
        DefraResult result = ACPDeleteDACActorRelationshipNative(this.txnPtr, 0, collection, docID, relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an actor relationship from a document access-control policy within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param collection collection name or collection selector encoded as JSON
     * @param docID document identifier
     * @param relation relationship name
     * @param actor actor DID
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPDeleteDACActorRelationship(String collection, String docID, String relation, String actor, DefraIdentity identity) throws DefraException {
        DefraResult result = ACPDeleteDACActorRelationshipNative(this.txnPtr, identity.getPointer(), collection, docID, relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Disables node access control within this transaction.
     *
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPDisableNAC() throws DefraException {
        DefraResult result = ACPDisableNACNative(this.txnPtr, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Disables node access control within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPDisableNAC(DefraIdentity identity) throws DefraException {
        DefraResult result = ACPDisableNACNative(this.txnPtr, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Re-enables node access control within this transaction.
     *
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPReEnableNAC() throws DefraException {
        DefraResult result = ACPReEnableNACNative(this.txnPtr, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Re-enables node access control within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPReEnableNAC(DefraIdentity identity) throws DefraException {
        DefraResult result = ACPReEnableNACNative(this.txnPtr, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds an actor relationship to the node access-control policy within this transaction.
     *
     * @param relation relationship name
     * @param actor actor DID
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPAddNACActorRelationship(String relation, String actor) throws DefraException {
        DefraResult result = ACPAddNACActorRelationshipNative(this.txnPtr, 0, relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds an actor relationship to the node access-control policy within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param relation relationship name
     * @param actor actor DID
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPAddNACActorRelationship(String relation, String actor, DefraIdentity identity) throws DefraException {
        DefraResult result = ACPAddNACActorRelationshipNative(this.txnPtr, identity.getPointer(), relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an actor relationship from the node access-control policy within this transaction.
     *
     * @param relation relationship name
     * @param actor actor DID
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPDeleteNACActorRelationship(String relation, String actor) throws DefraException {
        DefraResult result = ACPDeleteNACActorRelationshipNative(this.txnPtr, 0, relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an actor relationship from the node access-control policy within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param relation relationship name
     * @param actor actor DID
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPDeleteNACActorRelationship(String relation, String actor, DefraIdentity identity) throws DefraException {
        DefraResult result = ACPDeleteNACActorRelationshipNative(this.txnPtr, identity.getPointer(), relation, actor);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns the node access-control status as JSON within this transaction.
     *
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPGetNACStatus() throws DefraException {
        DefraResult result = ACPGetNACStatusNative(this.txnPtr, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns the node access-control status as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String ACPGetNACStatus(DefraIdentity identity) throws DefraException {
        DefraResult result = ACPGetNACStatusNative(this.txnPtr, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Backup Methods
    /**
     * Exports the current data, or a subset of it, to a file within this transaction.
     *
     * @param filepath path to the file to export to
     * @param collections names of the collections to export; all collections are exported if empty
     * @param format export format, or an empty string for the default (JSON)
     * @param pretty whether to pretty-print the output
     * @throws DefraException if DefraDB rejects the operation
     */
    public void basicExport(String filepath, String[] collections, String format, boolean pretty) throws DefraException {
        DefraResult result = BasicExportNative(this.txnPtr, filepath, String.join(",", collections), format, pretty);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
    }

    /**
     * Imports a JSON dataset from a file within this transaction.
     *
     * @param filepath path to the file to import, which must be accessible to the node
     * @throws DefraException if DefraDB rejects the operation
     */
    public void basicImport(String filepath) throws DefraException {
        DefraResult result = BasicImportNative(this.txnPtr, filepath);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
    }

    // Block Verification
    /**
     * Verifies the signature of a block within this transaction.
     *
     * @param keyType key type supported by DefraDB
     * @param publicKey public key encoded as hexadecimal
     * @param cid content identifier of the block
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String verifyBlockSignature(String keyType, String publicKey, String cid) throws DefraException {
        DefraResult result = VerifyBlockSignatureNative(this.txnPtr, keyType, publicKey, cid, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Verifies the signature of a block within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param keyType key type supported by DefraDB
     * @param publicKey public key encoded as hexadecimal
     * @param cid content identifier of the block
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String verifyBlockSignature(String keyType, String publicKey, String cid, DefraIdentity identity) throws DefraException {
        DefraResult result = VerifyBlockSignatureNative(this.txnPtr, keyType, publicKey, cid, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Collection Methods
    /**
     * Adds collection definitions from Schema Definition Language (SDL) within this transaction.
     *
     * @param sdl Schema Definition Language (SDL) collection definition
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addCollection(String sdl) throws DefraException {
        DefraResult result = AddCollectionNative(this.txnPtr, sdl, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds collection definitions from Schema Definition Language (SDL) within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param sdl Schema Definition Language (SDL) collection definition
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addCollection(String sdl, DefraIdentity identity) throws DefraException {
        DefraResult result = AddCollectionNative(this.txnPtr, sdl, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns matching collection descriptions as JSON within this transaction.
     *
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String describeCollection(DefraCollectionOptions options) throws DefraException {
        DefraResult result = DescribeCollectionNative(this.txnPtr, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns matching collection descriptions as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String describeCollection(DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = DescribeCollectionNative(this.txnPtr, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Applies a schema patch and optional migration lens configuration within this transaction.
     *
     * @param patch schema patch encoded as JSON
     * @param lensConfig migration lens configuration, or an empty string
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String patchCollection(String patch, String lensConfig) throws DefraException {
        DefraResult result = PatchCollectionNative(this.txnPtr, patch, lensConfig, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Applies a schema patch and optional migration lens configuration within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param patch schema patch encoded as JSON
     * @param lensConfig migration lens configuration, or an empty string
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String patchCollection(String patch, String lensConfig, DefraIdentity identity) throws DefraException {
        DefraResult result = PatchCollectionNative(this.txnPtr, patch, lensConfig, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Makes the selected collection version active within this transaction.
     *
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String setActiveCollection(DefraCollectionOptions options) throws DefraException {
        DefraResult result = SetActiveCollectionNative(this.txnPtr, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Makes the selected collection version active within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String setActiveCollection(DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = SetActiveCollectionNative(this.txnPtr, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes every document in the selected collection within this transaction.
     *
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String truncateCollection(DefraCollectionOptions options) throws DefraException {
        DefraResult result = TruncateCollectionNative(this.txnPtr, null, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes every document in the selected collection within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String truncateCollection(DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = TruncateCollectionNative(this.txnPtr, null, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Debug Methods
    /**
     * Logs the entire contents of the store as seen by this transaction.
     *
     * <p>It is likely unwise to call this on a large database.</p>
     *
     * @throws DefraException if DefraDB rejects the operation
     */
    public void printDump() throws DefraException {
        DefraResult result = PrintDumpNative(this.txnPtr);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
    }

    // Document Methods
    /**
     * Adds a JSON document to the selected collection within this transaction.
     *
     * @param json document encoded as JSON
     * @param isEncrypted whether to encrypt the document
     * @param encryptedFields comma-separated field names to encrypt
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addDocument(String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options) throws DefraException {
        DefraResult result = AddDocumentNative(this.txnPtr, json, isEncrypted, encryptedFields, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds a JSON document to the selected collection within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param json document encoded as JSON
     * @param isEncrypted whether to encrypt the document
     * @param encryptedFields comma-separated field names to encrypt
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addDocument(String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = AddDocumentNative(this.txnPtr, json, isEncrypted, encryptedFields, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes the document matching an ID and optional filter within this transaction.
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteDocument(String docID, String filter, DefraCollectionOptions options) throws DefraException {
        DefraResult result = DeleteDocumentNative(this.txnPtr, docID, filter, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes the document matching an ID and optional filter within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteDocument(String docID, String filter, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = DeleteDocumentNative(this.txnPtr, docID, filter, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns a document by ID as JSON within this transaction.
     *
     * @param docID document identifier
     * @param showDeleted whether a deleted document may be returned
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String getDocument(String docID, boolean showDeleted, DefraCollectionOptions options) throws DefraException {
        DefraResult result = GetDocumentNative(this.txnPtr, docID, showDeleted, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns a document by ID as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param docID document identifier
     * @param showDeleted whether a deleted document may be returned
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String getDocument(String docID, boolean showDeleted, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = GetDocumentNative(this.txnPtr, docID, showDeleted, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Updates the document matching an ID and optional filter within this transaction.
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param updater JSON update expression
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String updateDocument(String docID, String filter, String updater, DefraCollectionOptions options) throws DefraException {
        DefraResult result = UpdateDocumentNative(this.txnPtr, docID, filter, updater, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Updates the document matching an ID and optional filter within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param updater JSON update expression
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String updateDocument(String docID, String filter, String updater, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = UpdateDocumentNative(this.txnPtr, docID, filter, updater, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Encrypted Index Methods
    /**
     * Deletes an encrypted index from a collection field within this transaction.
     *
     * @param collectionName collection name
     * @param fieldName field name
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteEncryptedIndex(String collectionName, String fieldName) throws DefraException {
        DefraResult result = DeleteEncryptedIndexNative(this.txnPtr, collectionName, fieldName, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an encrypted index from a collection field within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param collectionName collection name
     * @param fieldName field name
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteEncryptedIndex(String collectionName, String fieldName, DefraIdentity identity) throws DefraException {
        DefraResult result = DeleteEncryptedIndexNative(this.txnPtr, collectionName, fieldName, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns encrypted-index descriptions as JSON within this transaction.
     *
     * @param collectionName collection name
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listEncryptedIndexes(String collectionName) throws DefraException {
        DefraResult result = ListEncryptedIndexesNative(this.txnPtr, collectionName, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns encrypted-index descriptions as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param collectionName collection name
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listEncryptedIndexes(String collectionName, DefraIdentity identity) throws DefraException {
        DefraResult result = ListEncryptedIndexesNative(this.txnPtr, collectionName, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an encrypted index on a collection field within this transaction.
     *
     * @param collectionName collection name
     * @param fieldName field name
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newEncryptedIndex(String collectionName, String fieldName) throws DefraException {
        DefraResult result = NewEncryptedIndexNative(this.txnPtr, collectionName, fieldName, null, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an encrypted index on a collection field within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param collectionName collection name
     * @param fieldName field name
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newEncryptedIndex(String collectionName, String fieldName, DefraIdentity identity) throws DefraException {
        DefraResult result = NewEncryptedIndexNative(this.txnPtr, collectionName, fieldName, null, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Identity Methods
    /**
     * Returns the public identity of the node as JSON within this transaction.
     *
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String getNodeIdentity() throws DefraException {
        DefraResult result = GetNodeIdentityNative(this.txnPtr);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Index Methods
    /**
     * Deletes an index from the selected collection within this transaction.
     *
     * @param indexName index name
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteIndex(String indexName, DefraCollectionOptions options) throws DefraException {
        DefraResult result = DeleteIndexNative(this.txnPtr, indexName, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an index from the selected collection within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param indexName index name
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteIndex(String indexName, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = DeleteIndexNative(this.txnPtr, indexName, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns index descriptions for the selected collection as JSON within this transaction.
     *
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listIndexes(DefraCollectionOptions options) throws DefraException {
        DefraResult result = ListIndexesNative(this.txnPtr, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns index descriptions for the selected collection as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listIndexes(DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = ListIndexesNative(this.txnPtr, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an index on the selected collection within this transaction.
     *
     * @param indexName index name
     * @param fields comma-separated fields with optional ASC or DESC order
     * @param isUnique whether indexed values must be unique
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newIndex(String indexName, String fields, boolean isUnique, DefraCollectionOptions options) throws DefraException {
        DefraResult result = NewIndexNative(this.txnPtr, indexName, fields, isUnique, null, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an index on the selected collection within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param indexName index name
     * @param fields comma-separated fields with optional ASC or DESC order
     * @param isUnique whether indexed values must be unique
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newIndex(String indexName, String fields, boolean isUnique, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = NewIndexNative(this.txnPtr, indexName, fields, isUnique, null, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Lens Methods
    /**
     * Adds a lens from its configuration within this transaction.
     *
     * @param cfg lens configuration encoded as JSON
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addLens(String cfg) throws DefraException {
        DefraResult result = AddLensNative(this.txnPtr, 0, cfg);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds a lens from its configuration within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param cfg lens configuration encoded as JSON
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addLens(String cfg, DefraIdentity identity) throws DefraException {
        DefraResult result = AddLensNative(this.txnPtr, identity.getPointer(), cfg);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns registered lens descriptions as JSON within this transaction.
     *
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listLenses() throws DefraException {
        DefraResult result = ListLensesNative(this.txnPtr, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Returns registered lens descriptions as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listLenses(DefraIdentity identity) throws DefraException {
        DefraResult result = ListLensesNative(this.txnPtr, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Sets a migration lens between collection versions within this transaction.
     *
     * @param src source collection version identifier
     * @param dst destination collection version identifier
     * @param cfg lens configuration encoded as JSON
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String setLens(String src, String dst, String cfg) throws DefraException {
        DefraResult result = SetLensNative(this.txnPtr, 0, src, dst, cfg);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Sets a migration lens between collection versions within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param src source collection version identifier
     * @param dst destination collection version identifier
     * @param cfg lens configuration encoded as JSON
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String setLens(String src, String dst, String cfg, DefraIdentity identity) throws DefraException {
        DefraResult result = SetLensNative(this.txnPtr, identity.getPointer(), src, dst, cfg);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Query Methods
    /**
     * Executes a GraphQL query and returns its result as JSON within this transaction.
     *
     * @param query GraphQL query
     * @param operationName GraphQL operation name, or an empty string
     * @param variables GraphQL variables encoded as JSON, or an empty string
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String executeQuery(String query, String operationName, String variables) throws DefraException {
        DefraResult result = ExecuteQueryNative(this.txnPtr, query, 0, operationName, variables);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Executes a GraphQL query and returns its result as JSON within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param query GraphQL query
     * @param operationName GraphQL operation name, or an empty string
     * @param variables GraphQL variables encoded as JSON, or an empty string
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String executeQuery(String query, String operationName, String variables, DefraIdentity identity) throws DefraException {
        DefraResult result = ExecuteQueryNative(this.txnPtr, query, identity.getPointer(), operationName, variables);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // View Methods
    /**
     * Creates a view from a query, SDL, and optional transform within this transaction.
     *
     * @param query GraphQL query
     * @param sdl Schema Definition Language (SDL) collection definition
     * @param transformCID content identifier of the view transform, or an empty string
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addView(String query, String sdl, String transformCID) throws DefraException {
        DefraResult result = AddViewNative(this.txnPtr, query, sdl, transformCID, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates a view from a query, SDL, and optional transform within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param query GraphQL query
     * @param sdl Schema Definition Language (SDL) collection definition
     * @param transformCID content identifier of the view transform, or an empty string
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addView(String query, String sdl, String transformCID, DefraIdentity identity) throws DefraException {
        DefraResult result = AddViewNative(this.txnPtr, query, sdl, transformCID, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Refreshes the selected view within this transaction.
     *
     * @param options collection selector and operation options
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String refreshView(DefraCollectionOptions options) throws DefraException {
        DefraResult result = RefreshViewNative(this.txnPtr, options, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Refreshes the selected view within this transaction.
     *
     * <p>If an identity parameter is present, it authorizes this operation.</p>
     *
     * @param options collection selector and operation options
     * @param identity identity authorizing the operation
     * @return the native operation result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String refreshView(DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        DefraResult result = RefreshViewNative(this.txnPtr, options, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

        // Helper function for getCollectionByName functions
    private DefraCollection getCollectionByNameWithIdentityPointer(String name, long identityPtr) throws DefraException {
		DefraCollectionOptions copts = new DefraCollectionOptions();
		copts.name = name;
		DefraResult describeResult = DescribeCollectionNative(this.txnPtr, copts, identityPtr);
		if (describeResult.status != 0) {
			throw new DefraException(describeResult.error);
		}
        String collectionID = "";
        String versionID = "";
        try {
            JSONArray array = new JSONArray(describeResult.value);
            if (array.length() == 0) {
            throw new DefraException("Collection with name '" + name + "' not found");
            }
            JSONObject first = array.getJSONObject(0);
            collectionID = first.getString("CollectionID");
            versionID = first.getString("VersionID");
        } catch (JSONException e) {
            throw new DefraException(e.getMessage());
        }
		return new DefraCollection(this.txnPtr, name, collectionID, versionID);
    }
	
	/**
	 * Returns a handle for the active collection with the given name within this transaction.
	 *
	 * @param name collection name
	 * @return a handle to the active collection
	 * @throws DefraException if DefraDB rejects the operation
	 */
	public DefraCollection getCollectionByName(String name) throws DefraException {
		return getCollectionByNameWithIdentityPointer(name, 0);		
	}
	
	/**
	 * Returns a handle for the active collection with the given name within this transaction.
	 *
	 * <p>If an identity parameter is present, it authorizes this operation.</p>
	 *
	 * @param name collection name
	 * @param identity identity authorizing the operation
	 * @return a handle to the active collection
	 * @throws DefraException if DefraDB rejects the operation
	 */
	public DefraCollection getCollectionByName(String name, DefraIdentity identity) throws DefraException {
		return getCollectionByNameWithIdentityPointer(name, identity.getPointer());
	}

    /**
     * Exports all collections to a file in JSON format within this transaction.
     *
     * @param filepath path to the file to export to
     * @throws DefraException if DefraDB rejects the operation
     */
    public void basicExport(String filepath) throws DefraException {
        basicExport(filepath, new String[0], "", false);
    }

    // Function is a getter for the transaction pointer
    /**
     * Returns the underlying native pointer.
     * @return the underlying native pointer
     */
    public long getPointer() {
        return this.txnPtr;
    }

    private static long pointer(DefraIdentity identity) {
        return identity == null ? 0 : identity.getPointer();
    }

    private static String value(DefraResult result) throws DefraException {
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Starts collecting the blocks the caller's writes in this transaction
     * create, so that {@link #batchSign(DefraIdentity)} signs them together.
     *
     * @param identity the caller's identity, or null for none
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String batchStart(DefraIdentity identity) throws DefraException {
        return value(BatchStartNative(this.txnPtr, pointer(identity)));
    }

    /**
     * Signs the blocks collected since {@link #batchStart(DefraIdentity)}.
     *
     * @param identity the caller's identity, or null for none
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String batchSign(DefraIdentity identity) throws DefraException {
        return value(BatchSignNative(this.txnPtr, pointer(identity)));
    }

    /**
     * Saves each document of a JSON array in this transaction: added if new,
     * updated if it exists.
     *
     * @param json the documents, as a JSON array of objects
     * @param isEncrypted whether to encrypt every field
     * @param encryptedFields comma-separated fields to encrypt, or an empty string
     * @param options the collection to save into
     * @param identity the caller's identity, or null for none
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String saveDocuments(String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        return value(SaveDocumentsNative(this.txnPtr, json, isEncrypted, encryptedFields, options, pointer(identity)));
    }

    /**
     * Permanently removes documents and their history from this node, when
     * this transaction commits; nothing is replicated.
     *
     * @param docIDs the documents to remove
     * @param options the collection holding them
     * @param identity the caller's identity, or null for none
     * @return the native JSON result, {@code {"Count": n}}
     * @throws DefraException if DefraDB rejects the operation
     */
    public String purgeDocuments(String[] docIDs, DefraCollectionOptions options, DefraIdentity identity) throws DefraException {
        return value(PurgeDocumentsNative(this.txnPtr, new JSONArray(docIDs).toString(), options, pointer(identity)));
    }

    /**
     * Executes a GraphQL request in this transaction without blocking the
     * calling thread.
     *
     * @param query the GraphQL request
     * @param operationName the operation to run, or an empty string
     * @param variables the variables as a JSON object, or an empty string
     * @param identity the caller's identity, or null for none
     * @return a future completed with the JSON response, or exceptionally
     *     with a {@link DefraException}
     */
    public java.util.concurrent.CompletableFuture<String> executeQueryAsync(String query, String operationName, String variables, DefraIdentity identity) {
        java.util.concurrent.CompletableFuture<DefraResult> raw = new java.util.concurrent.CompletableFuture<>();
        ExecuteQueryAsyncNative(this.txnPtr, query, pointer(identity), operationName, variables, NativeCallbacks.pendingQuery(raw));
        return NativeCallbacks.values(raw);
    }
}