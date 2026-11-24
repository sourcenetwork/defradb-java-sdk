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
 * A handle to a DefraDB collection version.
 *
 * <p>Collection operations use this handle's name and identifiers. Overloads
 * accepting a {@link DefraIdentity} execute as that identity; other overloads
 * use the node's default authorization context.</p>
 */
public class DefraCollection {

    static {
        NativeLoader.load("nativewrapper");
    }
	
    // Document Methods
    private native DefraResult AddDocumentNative(long nodePtr, String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options, long identityPtr);
    private native DefraResult DeleteDocumentNative(long nodePtr, String docID, String filter, DefraCollectionOptions options, long identityPtr);
    private native DefraResult GetDocumentNative(long nodePtr, String docID, boolean showDeleted, DefraCollectionOptions options, long identityPtr);
    private native DefraResult UpdateDocumentNative(long nodePtr, String docID, String filter, String updater, DefraCollectionOptions options, long identityPtr);

    // Encrypted Index Methods
    private native DefraResult DeleteEncryptedIndexNative(long nodePtr, String collectionName, String fieldName, long identityPtr);
    private native DefraResult ListEncryptedIndexesNative(long nodePtr, String collectionName, long identityPtr);
    private native DefraResult NewEncryptedIndexNative(long nodePtr, String collectionName, String fieldName, String indexType, long identityPtr);

    // Index Methods
    private native DefraResult DeleteIndexNative(long nodePtr, String indexName, DefraCollectionOptions options, long identityPtr);
    private native DefraResult ListIndexesNative(long nodePtr, DefraCollectionOptions options, long identityPtr);
    private native DefraResult NewIndexNative(long nodePtr, String indexName, String fields, boolean isUnique, String vectorJSON, DefraCollectionOptions options, long identityPtr);

    // Added with the Rust C API
    private native DefraResult SaveDocumentsNative(long nodePtr, String json, boolean isEncrypted, String encryptedFields, DefraCollectionOptions options, long identityPtr);
    private native DefraResult PurgeDocumentsNative(long nodePtr, String docIDsJSON, DefraCollectionOptions options, long identityPtr);

	private String name;
	private String collectionID;
	private String versionID;
	private long storePtr;
	
	/**
	 * Creates a collection handle backed by a native store or transaction.
	 *
	 * @param ptr native store or transaction pointer
	 * @param n collection name
	 * @param c collection identifier
	 * @param v collection version identifier
	 */
	public DefraCollection(long ptr, String n, String c, String v) {
		this.storePtr = ptr;
		this.name = n;
		this.collectionID = c;
		this.versionID = v;
	}
	
	private DefraCollectionOptions collectionOptionsFromThis() {
		DefraCollectionOptions opts = new DefraCollectionOptions();
		opts.version = this.versionID;
		opts.collectionID = this.collectionID;
		opts.name = this.name;
		return opts;
	}
	
	/**
	 * Returns the collection name.
	 *
	 * @return the collection name
	 */
	public String Name() {
		return this.name;
	}
	
	/**
	 * Returns the collection version identifier.
	 *
	 * @return the collection version identifier
	 */
	public String VersionID() {
		return this.versionID;
	}
	
	/**
	 * Returns the collection identifier.
	 *
	 * @return the collection identifier
	 */
	public String CollectionID() {
		return this.collectionID;
	}
	
    // Document Methods
    /**
     * Adds a JSON document to this collection.
     *
     * @param json document encoded as JSON
     * @param isEncrypted whether to encrypt the document
     * @param encryptedFields comma-separated field names to encrypt
     * @return the native JSON result, including the assigned document ID
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addDocument(String json, boolean isEncrypted, String encryptedFields) throws DefraException {
        DefraResult result = AddDocumentNative(this.storePtr, json, isEncrypted, encryptedFields, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Adds a JSON document to this collection as an identity.
     *
     * @param json document encoded as JSON
     * @param isEncrypted whether to encrypt the document
     * @param encryptedFields comma-separated field names to encrypt
     * @param identity identity authorizing the operation
     * @return the native JSON result, including the assigned document ID
     * @throws DefraException if DefraDB rejects the operation
     */
    public String addDocument(String json, boolean isEncrypted, String encryptedFields, DefraIdentity identity) throws DefraException {
        DefraResult result = AddDocumentNative(this.storePtr, json, isEncrypted, encryptedFields, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes the document matching an ID and optional filter.
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @return the native JSON deletion result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteDocument(String docID, String filter) throws DefraException {
        DefraResult result = DeleteDocumentNative(this.storePtr, docID, filter, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes the document matching an ID and optional filter as an identity.
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param identity identity authorizing the operation
     * @return the native JSON deletion result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteDocument(String docID, String filter, DefraIdentity identity) throws DefraException {
        DefraResult result = DeleteDocumentNative(this.storePtr, docID, filter, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Gets a document by ID.
     *
     * @param docID document identifier
     * @param showDeleted whether a deleted document may be returned
     * @return the document encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String getDocument(String docID, boolean showDeleted) throws DefraException {
        DefraResult result = GetDocumentNative(this.storePtr, docID, showDeleted, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Gets a document by ID as an identity.
     *
     * @param docID document identifier
     * @param showDeleted whether a deleted document may be returned
     * @param identity identity authorizing the operation
     * @return the document encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String getDocument(String docID, boolean showDeleted, DefraIdentity identity) throws DefraException {
        DefraResult result = GetDocumentNative(this.storePtr, docID, showDeleted, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Updates the document matching an ID and optional filter.
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param updater JSON update expression
     * @return the native JSON update result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String updateDocument(String docID, String filter, String updater) throws DefraException {
        DefraResult result = UpdateDocumentNative(this.storePtr, docID, filter, updater, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Updates the document matching an ID and optional filter as an identity.
     *
     * @param docID document identifier
     * @param filter JSON filter that must match, or an empty string
     * @param updater JSON update expression
     * @param identity identity authorizing the operation
     * @return the native JSON update result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String updateDocument(String docID, String filter, String updater, DefraIdentity identity) throws DefraException {
        DefraResult result = UpdateDocumentNative(this.storePtr, docID, filter, updater, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Encrypted Index Methods
    /**
     * Deletes an encrypted index from a collection field.
     *
     * @param collectionName collection name
     * @param fieldName indexed field name
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteEncryptedIndex(String collectionName, String fieldName) throws DefraException {
        DefraResult result = DeleteEncryptedIndexNative(this.storePtr, collectionName, fieldName, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an encrypted index from a collection field as an identity.
     *
     * @param collectionName collection name
     * @param fieldName indexed field name
     * @param identity identity authorizing the operation
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteEncryptedIndex(String collectionName, String fieldName, DefraIdentity identity) throws DefraException {
        DefraResult result = DeleteEncryptedIndexNative(this.storePtr, collectionName, fieldName, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Lists encrypted indexes for a collection.
     *
     * @param collectionName collection name
     * @return encrypted-index descriptions encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listEncryptedIndexes(String collectionName) throws DefraException {
        DefraResult result = ListEncryptedIndexesNative(this.storePtr, collectionName, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Lists encrypted indexes for a collection as an identity.
     *
     * @param collectionName collection name
     * @param identity identity authorizing the operation
     * @return encrypted-index descriptions encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listEncryptedIndexes(String collectionName, DefraIdentity identity) throws DefraException {
        DefraResult result = ListEncryptedIndexesNative(this.storePtr, collectionName, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an encrypted index on a collection field.
     *
     * @param collectionName collection name
     * @param fieldName indexed field name
     * @return the encrypted-index description encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newEncryptedIndex(String collectionName, String fieldName) throws DefraException {
        DefraResult result = NewEncryptedIndexNative(this.storePtr, collectionName, fieldName, null, 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an encrypted index on a collection field as an identity.
     *
     * @param collectionName collection name
     * @param fieldName indexed field name
     * @param identity identity authorizing the operation
     * @return the encrypted-index description encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newEncryptedIndex(String collectionName, String fieldName, DefraIdentity identity) throws DefraException {
        DefraResult result = NewEncryptedIndexNative(this.storePtr, collectionName, fieldName, null, identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    // Index Methods
    /**
     * Deletes an index from this collection.
     *
     * @param indexName index name
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteIndex(String indexName) throws DefraException {
        DefraResult result = DeleteIndexNative(this.storePtr, indexName, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Deletes an index from this collection as an identity.
     *
     * @param indexName index name
     * @param identity identity authorizing the operation
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String deleteIndex(String indexName, DefraIdentity identity) throws DefraException {
        DefraResult result = DeleteIndexNative(this.storePtr, indexName, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Lists indexes on this collection.
     *
     * @return index descriptions encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listIndexes() throws DefraException {
        DefraResult result = ListIndexesNative(this.storePtr, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Lists indexes on this collection as an identity.
     *
     * @param identity identity authorizing the operation
     * @return index descriptions encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String listIndexes(DefraIdentity identity) throws DefraException {
        DefraResult result = ListIndexesNative(this.storePtr, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an index on this collection.
     *
     * @param indexName index name
     * @param fields comma-separated fields with optional {@code :ASC} or {@code :DESC} order
     * @param isUnique whether indexed values must be unique
     * @return the index description encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newIndex(String indexName, String fields, boolean isUnique) throws DefraException {
        DefraResult result = NewIndexNative(this.storePtr, indexName, fields, isUnique, null, collectionOptionsFromThis(), 0);
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Creates an index on this collection as an identity.
     *
     * @param indexName index name
     * @param fields comma-separated fields with optional {@code :ASC} or {@code :DESC} order
     * @param isUnique whether indexed values must be unique
     * @param identity identity authorizing the operation
     * @return the index description encoded as JSON
     * @throws DefraException if DefraDB rejects the operation
     */
    public String newIndex(String indexName, String fields, boolean isUnique, DefraIdentity identity) throws DefraException {
        DefraResult result = NewIndexNative(this.storePtr, indexName, fields, isUnique, null, collectionOptionsFromThis(), identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Saves each document of a JSON array in this collection: added if new,
     * updated if it exists.
     *
     * @param json the documents, as a JSON array of objects
     * @param isEncrypted whether to encrypt every field
     * @param encryptedFields comma-separated fields to encrypt, or an empty string
     * @param identity the caller's identity, or null for none
     * @return the native JSON result
     * @throws DefraException if DefraDB rejects the operation
     */
    public String saveDocuments(String json, boolean isEncrypted, String encryptedFields, DefraIdentity identity) throws DefraException {
        DefraResult result = SaveDocumentsNative(this.storePtr, json, isEncrypted, encryptedFields, collectionOptionsFromThis(), identity == null ? 0 : identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }

    /**
     * Permanently removes documents of this collection and their history
     * from this node only; nothing is replicated.
     *
     * @param docIDs the documents to remove
     * @param identity the caller's identity, or null for none
     * @return the native JSON result, {@code {"Count": n}}
     * @throws DefraException if DefraDB rejects the operation
     */
    public String purgeDocuments(String[] docIDs, DefraIdentity identity) throws DefraException {
        DefraResult result = PurgeDocumentsNative(this.storePtr, new org.json.JSONArray(java.util.Arrays.asList(docIDs)).toString(), collectionOptionsFromThis(), identity == null ? 0 : identity.getPointer());
        if (result.status != 0) {
            throw new DefraException(result.error);
        }
        return result.value;
    }
}
