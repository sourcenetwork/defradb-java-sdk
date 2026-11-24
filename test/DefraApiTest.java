import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import source.defra.DefraCollection;
import source.defra.DefraCollectionOptions;
import source.defra.DefraException;
import source.defra.DefraIdentity;
import source.defra.DefraNode;
import source.defra.DefraNodeInitOptions;
import source.defra.DefraTransaction;

/**
 * Calls every public method of the SDK against real nodes. A method passes
 * when it returns, or throws a DefraException carrying DefraDB's message: the
 * call crossed JNI and reached DefraDB. Anything else (an unbound native, a
 * field the wrapper cannot find, a crash) fails it. The core flows must also
 * give the right results.
 */
public class DefraApiTest {
    private static final List<String> failures = new ArrayList<>();
    private static int passed = 0;

    interface Call {
        Object run() throws Exception;
    }

    /** `call` must return without throwing. */
    static Object ok(String name, Call call) {
        try {
            Object value = call.run();
            passed++;
            return value;
        } catch (Throwable t) {
            failures.add(name + ": " + t);
            return null;
        }
    }

    /** `call` must reach DefraDB: return, or throw DefraDB's own error. */
    static void reaches(String name, Call call) {
        try {
            call.run();
            passed++;
        } catch (DefraException e) {
            if (e.getMessage() == null || e.getMessage().isEmpty()) {
                failures.add(name + ": a DefraException with no message");
            } else {
                passed++;
            }
        } catch (Throwable t) {
            failures.add(name + ": " + t);
        }
    }

    /** `call` must throw a DefraException whose message contains `expected`. */
    static void refuses(String name, String expected, Call call) {
        try {
            call.run();
            failures.add(name + ": expected a refusal containing \"" + expected + "\"");
        } catch (DefraException e) {
            if (e.getMessage() != null && e.getMessage().contains(expected)) {
                passed++;
            } else {
                failures.add(name + ": refused with \"" + e.getMessage() + "\", expected \"" + expected + "\"");
            }
        } catch (Throwable t) {
            failures.add(name + ": " + t);
        }
    }

    static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
        } else {
            failures.add(name + ": " + detail);
        }
    }

    static DefraCollectionOptions users() {
        DefraCollectionOptions options = new DefraCollectionOptions();
        options.name = "User";
        return options;
    }

    static final String POLICY = String.join("\n",
        "name: Test Policy",
        "resources:",
        "  - name: users",
        "    relations:",
        "      - name: reader",
        "      - name: editor",
        "    permissions:",
        "      - name: read",
        "        expr: reader",
        "      - name: update",
        "        expr: editor",
        "      - name: delete",
        "        expr: editor");

    public static void main(String[] args) throws Exception {
        DefraNodeInitOptions options = new DefraNodeInitOptions();
        options.inMemory = true;
        DefraNode node = (DefraNode) ok("new DefraNode", () -> new DefraNode(options));
        if (node == null) {
            report();
            return;
        }

        // Version, node and identities.
        ok("getVersion", () -> node.getVersion(false, false));
        ok("getVersion full json", () -> node.getVersion(true, true));
        ok("getNodeOptions", node::getNodeOptions);
        ok("getNodeIdentity", node::getNodeIdentity);
        DefraIdentity owner = (DefraIdentity) ok("identityNew secp256k1", () -> node.identityNew("secp256k1"));
        DefraIdentity reader = (DefraIdentity) ok("identityNew ed25519", () -> node.identityNew("ed25519"));
        String ownerKey = (String) ok("exportIdentityPrivateKey", () -> node.exportIdentityPrivateKey(owner));
        DefraIdentity imported = (DefraIdentity) ok("identityFromPrivateKey", () -> node.identityFromPrivateKey(ownerKey));
        refuses("identityFromPrivateKey malformed", "", () -> node.identityFromPrivateKey("not hex"));
        ok("freeIdentity", () -> { node.freeIdentity(imported); return null; });

        // Collections.
        ok("addCollection", () -> node.addCollection("type User { name: String age: Int }"));
        String described = (String) ok("describeCollection", () -> node.describeCollection(users()));
        check("describeCollection names User", described != null && described.contains("\"User\""), String.valueOf(described));
        DefraCollection collection = (DefraCollection) ok("getCollectionByName", () -> node.getCollectionByName("User"));
        ok("getCollectionByName as identity", () -> node.getCollectionByName("User", owner));
        reaches("patchCollection", () -> node.patchCollection("[]", ""));
        ok("addCollection Patched", () -> node.addCollection("type Patched { a: Int }"));
        ok("patchCollection adds a field", () -> node.patchCollection(
            "[{\"op\": \"add\", \"path\": \"/Patched/Fields/-\", \"value\": {\"Name\": \"patchedField\", \"Kind\": \"String\"}}]", ""));
        String patched = (String) ok("describeCollection after patch", () -> {
            DefraCollectionOptions p = new DefraCollectionOptions();
            p.name = "Patched";
            return node.describeCollection(p);
        });
        check("describeCollection shows the patched field", patched != null && patched.contains("patchedField"), String.valueOf(patched));
        reaches("setActiveCollection", () -> {
            DefraCollectionOptions version = users();
            version.version = collection.VersionID();
            return node.setActiveCollection(version);
        });

        // Documents, through the node and through a collection handle.
        String added = (String) ok("addDocument", () -> node.addDocument("{\"name\":\"Alice\",\"age\":30}", false, "", users()));
        String docID = added == null ? "" : added.replaceAll("(?s).*\"(bae-[0-9a-f-]+)\".*", "$1");
        check("addDocument answers a DocID", docID.startsWith("bae-"), String.valueOf(added));
        String got = (String) ok("getDocument", () -> node.getDocument(docID, false, users()));
        check("getDocument returns Alice", got != null && got.contains("Alice"), String.valueOf(got));
        ok("updateDocument", () -> node.updateDocument(docID, "", "{\"age\":31}", users()));
        String updated = (String) ok("getDocument after update", () -> node.getDocument(docID, false, users()));
        check("updateDocument changes age", updated != null && updated.contains("31"), String.valueOf(updated));
        String bob = (String) ok("collection addDocument", () -> collection.addDocument("{\"name\":\"Bob\"}", false, ""));
        String bobID = bob == null ? "" : bob.replaceAll("(?s).*\"(bae-[0-9a-f-]+)\".*", "$1");
        ok("collection getDocument", () -> collection.getDocument(bobID, false));
        ok("collection updateDocument", () -> collection.updateDocument(bobID, "", "{\"age\":5}"));
        ok("collection deleteDocument", () -> collection.deleteDocument(bobID, ""));
        ok("addDocument signed", () -> {
            DefraCollectionOptions signed = users();
            signed.enableSigning = Boolean.TRUE;
            return node.addDocument("{\"name\":\"Signed\"}", false, "", signed);
        });
        ok("addDocument encrypted", () -> node.addDocument("{\"name\":\"Secret\"}", true, "", users()));

        // Queries and subscriptions.
        String queried = (String) ok("executeQuery", () -> node.executeQuery("{ User { name } }", "", ""));
        check("executeQuery sees Alice", queried != null && queried.contains("Alice"), String.valueOf(queried));
        String filtered = (String) ok("executeQuery with variables", () -> node.executeQuery(
            "query Q($n: String) { User(filter: {name: {_eq: $n}}) { name } }", "Q", "{\"n\":\"Alice\"}"));
        check("variables reach the query", filtered != null && filtered.contains("Alice") && !filtered.contains("Signed"), String.valueOf(filtered));
        String picked = (String) ok("executeQuery picks the operation", () -> node.executeQuery(
            "query A { User(filter: {name: {_eq: \"Alice\"}}) { name } } query S { User(filter: {name: {_eq: \"Signed\"}}) { name } }", "S", ""));
        check("operationName picks the operation", picked != null && picked.contains("Signed") && !picked.contains("Alice"), String.valueOf(picked));
        String lone = docIDOf((String) ok("addDocument with an unpaired surrogate", () -> node.addDocument("{\"name\":\"lone\ud800x\"}", false, "", users())));
        String loneGot = (String) ok("getDocument with an unpaired surrogate", () -> node.getDocument(lone, false, users()));
        check("an unpaired surrogate arrives as U+FFFD", loneGot != null && loneGot.contains("lone\ufffdx"), String.valueOf(loneGot));
        CountDownLatch event = new CountDownLatch(1);
        String subscription = (String) ok("subscribe", () -> node.subscribe("subscription { User { name } }", e -> event.countDown()));
        ok("executeQuery mutation", () -> node.executeQuery("mutation { add_User(input: {name: \"Carol\"}) { _docID } }", "", ""));
        check("subscription delivers an event", event.await(10, TimeUnit.SECONDS), "no event within 10s");
        ok("unsubscribe", () -> { node.unsubscribe(subscription); return null; });

        // Indexes.
        ok("newIndex", () -> node.newIndex("by_name", "name", false, users()));
        String indexes = (String) ok("listIndexes", () -> node.listIndexes(users()));
        check("listIndexes names by_name", indexes != null && indexes.contains("by_name"), String.valueOf(indexes));
        ok("deleteIndex", () -> node.deleteIndex("by_name", users()));
        ok("collection newIndex", () -> collection.newIndex("by_age", "age", false));
        ok("collection listIndexes", collection::listIndexes);
        ok("collection deleteIndex", () -> collection.deleteIndex("by_age"));
        reaches("newEncryptedIndex", () -> node.newEncryptedIndex("User", "name"));
        reaches("listEncryptedIndexes", () -> node.listEncryptedIndexes("User"));
        reaches("deleteEncryptedIndex", () -> node.deleteEncryptedIndex("User", "name"));
        reaches("collection newEncryptedIndex", () -> collection.newEncryptedIndex("User", "age"));
        reaches("collection listEncryptedIndexes", () -> collection.listEncryptedIndexes("User"));
        reaches("collection deleteEncryptedIndex", () -> collection.deleteEncryptedIndex("User", "age"));

        // Transactions: writes are visible in the transaction, and outside it
        // only once it commits.
        DefraTransaction txn = (DefraTransaction) ok("transactionCreate", () -> node.transactionCreate(false));
        ok("txn addDocument", () -> txn.addDocument("{\"name\":\"Dave\"}", false, "", users()));
        String inside = (String) ok("txn executeQuery", () -> txn.executeQuery("{ User { name } }", "", ""));
        String outside = (String) ok("executeQuery beside txn", () -> node.executeQuery("{ User { name } }", "", ""));
        check("txn sees its write", inside != null && inside.contains("Dave"), String.valueOf(inside));
        check("node does not see the uncommitted write", outside != null && !outside.contains("Dave"), String.valueOf(outside));
        ok("txn getDocument", () -> txn.getDocument(docID, false, users()));
        ok("txn commit", txn::commit);
        String committed = (String) ok("executeQuery after commit", () -> node.executeQuery("{ User { name } }", "", ""));
        check("commit makes the write visible", committed != null && committed.contains("Dave"), String.valueOf(committed));
        check("Alice survives the commit", committed != null && committed.contains("Alice"), String.valueOf(committed));

        // Every other transaction call, in a transaction that is discarded.
        ok("addCollection Scratch", () -> node.addCollection("type Scratch { n: Int }"));
        ok("executeQuery add Scratch", () -> node.executeQuery("mutation { add_Scratch(input: {n: 1}) { _docID } }", "", ""));
        DefraTransaction other = (DefraTransaction) ok("transactionCreate for the rest", () -> node.transactionCreate(false));
        exerciseTransaction(other, collection, docID, owner);
        ok("txn discard", () -> { other.discard(); return null; });
        String afterDiscard = (String) ok("executeQuery after discard", () -> node.executeQuery("{ User { name age } }", "", ""));
        check("a discarded transaction changes nothing", afterDiscard != null && afterDiscard.contains("Alice")
            && afterDiscard.contains("31") && !afterDiscard.contains("40"), String.valueOf(afterDiscard));
        // Truncate is not transactional, in Go as in Rust: it applies at once.
        String scratch = (String) ok("executeQuery Scratch", () -> node.executeQuery("{ Scratch { n } }", "", ""));
        check("a truncate through a transaction stands", scratch != null && scratch.contains("[]"), String.valueOf(scratch));

        DefraTransaction readOnly = (DefraTransaction) ok("transactionCreate read-only", () -> node.transactionCreate(true));
        ok("read-only txn executeQuery", () -> readOnly.executeQuery("{ User { name } }", "", ""));
        ok("read-only txn discard", () -> { readOnly.discard(); return null; });
        DefraTransaction collectionTxn = (DefraTransaction) ok("transactionCreate for collection", () -> node.transactionCreate(false));
        DefraCollection txnCollection = (DefraCollection) ok("txn getCollectionByName", () -> collectionTxn.getCollectionByName("User"));
        ok("txn collection addDocument", () -> txnCollection.addDocument("{\"name\":\"Erin\"}", false, ""));
        ok("txn collection discard", () -> { collectionTxn.discard(); return null; });

        documentAccessControl(node, owner, reader);
        rustAdditions(node, owner, collection);
        rest(node, docID);
    }

    static void exerciseTransaction(DefraTransaction txn, DefraCollection collection, String docID, DefraIdentity owner) {
        ok("txn describeCollection", () -> txn.describeCollection(users()));
        ok("txn getCollectionByName", () -> txn.getCollectionByName("User"));
        ok("txn getNodeIdentity", txn::getNodeIdentity);
        ok("txn newIndex", () -> txn.newIndex("txn_idx", "name", false, users()));
        ok("txn listIndexes", () -> txn.listIndexes(users()));
        ok("txn deleteIndex", () -> txn.deleteIndex("txn_idx", users()));
        ok("txn getDocument", () -> txn.getDocument(docID, false, users()));
        ok("txn updateDocument", () -> txn.updateDocument(docID, "", "{\"age\":40}", users()));
        reaches("txn listLenses", txn::listLenses);
        reaches("txn newEncryptedIndex", () -> txn.newEncryptedIndex("User", "name"));
        reaches("txn listEncryptedIndexes", () -> txn.listEncryptedIndexes("User"));
        reaches("txn deleteEncryptedIndex", () -> txn.deleteEncryptedIndex("User", "name"));
        reaches("txn patchCollection", () -> txn.patchCollection("[]", ""));
        reaches("txn setActiveCollection", () -> {
            DefraCollectionOptions version = users();
            version.version = collection.VersionID();
            return txn.setActiveCollection(version);
        });
        ok("txn truncateCollection", () -> {
            DefraCollectionOptions scratch = new DefraCollectionOptions();
            scratch.name = "Scratch";
            return txn.truncateCollection(scratch);
        });
        reaches("txn refreshView", () -> txn.refreshView(users()));
        reaches("txn addView", () -> txn.addView("User { name }", "type TxnView { name: String }", ""));
        reaches("txn verifyBlockSignature", () -> txn.verifyBlockSignature("secp256k1", "00", "bafy"));
        // DefraDB refuses these through a transaction handle.
        for (String[] refusal : new String[][] { { "ACPAddDACPolicy" }, { "printDump" }, { "basicExport" }, { "basicImport" } }) {
            String call = refusal[0];
            refuses("txn " + call, "in a transaction", () -> {
                switch (call) {
                    case "ACPAddDACPolicy": return txn.ACPAddDACPolicy(POLICY, owner);
                    case "printDump": txn.printDump(); return null;
                    case "basicExport": txn.basicExport(new File(System.getProperty("java.io.tmpdir"), "txn.json").getPath()); return null;
                    default: txn.basicImport(new File(System.getProperty("java.io.tmpdir"), "txn.json").getPath()); return null;
                }
            });
        }
        reaches("txn ACPDisableNAC", () -> txn.ACPDisableNAC(owner));
        reaches("txn ACPReEnableNAC", () -> txn.ACPReEnableNAC(owner));
        reaches("txn ACPGetNACStatus", () -> txn.ACPGetNACStatus(owner));
        reaches("txn ACPAddNACActorRelationship", () -> txn.ACPAddNACActorRelationship("admin", "did:key:z6MkTest", owner));
        reaches("txn ACPDeleteNACActorRelationship", () -> txn.ACPDeleteNACActorRelationship("admin", "did:key:z6MkTest", owner));
        reaches("txn ACPAddDACActorRelationship", () -> txn.ACPAddDACActorRelationship("User", docID, "reader", "did:key:z6MkTest", owner));
        reaches("txn ACPDeleteDACActorRelationship", () -> txn.ACPDeleteDACActorRelationship("User", docID, "reader", "did:key:z6MkTest", owner));
    }

    static void documentAccessControl(DefraNode node, DefraIdentity owner, DefraIdentity reader) throws Exception {
        String policy = (String) ok("ACPAddDACPolicy", () -> node.ACPAddDACPolicy(POLICY, owner));
        String policyID = policy == null ? "" : policy.replaceAll("(?s).*\"PolicyID\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        ok("addCollection with policy", () -> node.addCollection(
            "type Secret @policy(id: \"" + policyID + "\", resource: \"users\") { text: String }", owner));
        DefraCollectionOptions secrets = new DefraCollectionOptions();
        secrets.name = "Secret";
        String secret = (String) ok("addDocument owned", () -> node.addDocument("{\"text\":\"hi\"}", false, "", secrets, owner));
        String secretID = secret == null ? "" : secret.replaceAll("(?s).*\"(bae-[0-9a-f-]+)\".*", "$1");
        String readerDID = (String) ok("reader DID", () -> {
            DefraNodeInitOptions readerOptions = new DefraNodeInitOptions();
            readerOptions.inMemory = true;
            readerOptions.identity = reader;
            DefraNode readerNode = new DefraNode(readerOptions);
            String identity = readerNode.getNodeIdentity();
            readerNode.close();
            return identity.replaceAll("(?s).*\"DID\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        });
        String hidden = (String) ok("query as reader before grant", () -> node.executeQuery("{ Secret { text } }", "", "", reader));
        check("reader cannot see the secret", hidden != null && !hidden.contains("hi"), String.valueOf(hidden));
        ok("ACPAddDACActorRelationship", () -> node.ACPAddDACActorRelationship("Secret", secretID, "reader", readerDID, owner));
        String shown = (String) ok("query as reader after grant", () -> node.executeQuery("{ Secret { text } }", "", "", reader));
        check("reader sees the secret", shown != null && shown.contains("hi"), String.valueOf(shown));
        ok("ACPDeleteDACActorRelationship", () -> node.ACPDeleteDACActorRelationship("Secret", secretID, "reader", readerDID, owner));
        ok("listActions", () -> node.listActions(owner));

        // Node access control, on a node that has it.
        DefraNodeInitOptions nacOptions = new DefraNodeInitOptions();
        nacOptions.inMemory = true;
        nacOptions.enableNodeACP = true;
        nacOptions.identity = owner;
        DefraNode nacNode = (DefraNode) ok("new DefraNode with NAC", () -> new DefraNode(nacOptions));
        if (nacNode != null) {
            String status = (String) ok("ACPGetNACStatus", () -> nacNode.ACPGetNACStatus(owner));
            check("NAC is enabled", status != null && status.contains("enabled"), String.valueOf(status));
            ok("ACPAddNACActorRelationship", () -> nacNode.ACPAddNACActorRelationship("admin", readerDID, owner));
            ok("ACPDeleteNACActorRelationship", () -> nacNode.ACPDeleteNACActorRelationship("admin", readerDID, owner));
            ok("ACPDisableNAC", () -> nacNode.ACPDisableNAC(owner));
            ok("ACPReEnableNAC", () -> nacNode.ACPReEnableNAC(owner));
            ok("close NAC node", () -> { nacNode.close(); return null; });
        }
    }

    static void rest(DefraNode node, String docID) throws Exception {
        String beforeDelete = (String) ok("query before deleteDocument", () -> node.getDocument(docID, false, users()));
        check("Alice is there before the end", beforeDelete != null && beforeDelete.contains("Alice"), String.valueOf(beforeDelete));

        // Backup and the dump.
        File backup = new File(Files.createTempDirectory("defra").toFile(), "backup.json");
        ok("basicExport", () -> { node.basicExport(backup.getPath()); return null; });
        check("basicExport writes the file", backup.length() > 0, "empty or missing " + backup);
        ok("basicExport some collections", () -> { node.basicExport(backup.getPath(), new String[] { "User" }, "json", true); return null; });
        ok("addCollection for import", () -> node.addCollection("type Imported { name: String }"));
        File importFile = new File(backup.getParentFile(), "import.json");
        Files.write(importFile.toPath(), "{\"Imported\": [{\"name\": \"Zed\"}]}".getBytes("UTF-8"));
        ok("basicImport", () -> { node.basicImport(importFile.getPath()); return null; });
        String imported2 = (String) ok("query imported", () -> node.executeQuery("{ Imported { name } }", "", ""));
        check("basicImport lands the document", imported2 != null && imported2.contains("Zed"), String.valueOf(imported2));
        ok("printDump", () -> { node.printDump(); return null; });
        reaches("verifyBlockSignature", () -> node.verifyBlockSignature("secp256k1", "00", "bafy"));

        // Lenses and views.
        reaches("addLens", () -> node.addLens("{\"lenses\": []}"));
        ok("listLenses", node::listLenses);
        reaches("setLens", () -> node.setLens("a", "b", "{\"lenses\": []}"));
        ok("addView", () -> node.addView("User { name }", "type UserView { name: String }", ""));
        DefraCollectionOptions view = new DefraCollectionOptions();
        view.name = "UserView";
        reaches("refreshView", () -> node.refreshView(view));

        // Truncating and deleting collections.
        ok("addCollection to delete", () -> node.addCollection("type Gone { x: Int }"));
        DefraCollectionOptions gone = new DefraCollectionOptions();
        gone.name = "Gone";
        ok("truncateCollection", () -> node.truncateCollection(gone));
        ok("deleteCollection", () -> { node.deleteCollection(new String[] { "Gone" }, false); return null; });
        ok("deleteDocument", () -> node.deleteDocument(docID, "", users()));
        ok("getDocument deleted", () -> node.getDocument(docID, true, users()));

        // Peer-to-peer: refused without P2P, working on a node with it.
        reaches("getP2PInfo without P2P", node::getP2PInfo);
        DefraNodeInitOptions p2pOptions = new DefraNodeInitOptions();
        p2pOptions.inMemory = true;
        p2pOptions.disableP2P = false;
        p2pOptions.listeningAddresses = "/ip4/127.0.0.1/tcp/0";
        DefraNode p2p = (DefraNode) ok("new DefraNode with P2P", () -> new DefraNode(p2pOptions));
        if (p2p != null) {
            ok("p2p addCollection", () -> p2p.addCollection("type User { name: String }"));
            String info = (String) ok("getP2PInfo", p2p::getP2PInfo);
            ok("listP2PActivePeers", p2p::listP2PActivePeers);
            ok("addP2PCollection", () -> p2p.addP2PCollection("User"));
            ok("listP2PCollections", p2p::listP2PCollections);
            reaches("deleteP2PCollection", () -> p2p.deleteP2PCollection("User"));
            String p2pDoc = (String) ok("p2p addDocument", () -> p2p.addDocument("{\"name\":\"P\"}", false, "", users()));
            String p2pDocID = p2pDoc == null ? "" : p2pDoc.replaceAll("(?s).*\"(bae-[0-9a-f-]+)\".*", "$1");
            ok("addP2PDocument", () -> p2p.addP2PDocument(p2pDocID));
            ok("listP2PDocuments", p2p::listP2PDocuments);
            reaches("deleteP2PDocument", () -> p2p.deleteP2PDocument(p2pDocID));
            ok("listP2PReplicators", p2p::listP2PReplicators);
            reaches("connectP2PPeers", () -> p2p.connectP2PPeers("/ip4/127.0.0.1/tcp/1/p2p/12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN"));
            reaches("disconnectP2PPeers", () -> p2p.disconnectP2PPeers("12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN"));
            reaches("addP2PReplicator", () -> p2p.addP2PReplicator("User", info == null ? "" : info));
            reaches("deleteP2PReplicator", () -> p2p.deleteP2PReplicator("User", "12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN"));
            reaches("syncP2PDocuments", () -> p2p.syncP2PDocuments("User", p2pDocID, 1000L));
            reaches("syncP2PDocuments as identity", () -> p2p.syncP2PDocuments("User", p2pDocID, 1000L, null));
            reaches("syncP2PCollectionVersions", () -> p2p.syncP2PCollectionVersions(p2p.getCollectionByName("User").VersionID(), 500L));
            reaches("syncP2PBranchableCollection", () -> p2p.syncP2PBranchableCollection(p2p.getCollectionByName("User").CollectionID(), 60_000L));
            reaches("syncP2PDocuments Go duration", () -> p2p.syncP2PDocuments("User", p2pDocID, "1s"));
            reaches("syncP2PDocuments Go duration under a millisecond", () -> p2p.syncP2PDocuments("User", p2pDocID, "500us"));
            reaches("syncP2PCollectionVersions Go duration", () -> p2p.syncP2PCollectionVersions(p2p.getCollectionByName("User").VersionID(), "1m30s"));
            reaches("syncP2PBranchableCollection Go duration", () -> p2p.syncP2PBranchableCollection(p2p.getCollectionByName("User").CollectionID(), "1.5h"));
            refuses("syncP2PDocuments bad timeout", "invalid timeout", () -> p2p.syncP2PDocuments("User", p2pDocID, "soon"));
            ok("syncP2PDocuments negative timeout", () -> {
                try {
                    p2p.syncP2PDocuments("User", p2pDocID, -1000L);
                    throw new AssertionError("a negative timeout was accepted");
                } catch (IllegalArgumentException expected) {
                    return null;
                }
            });
            ok("close P2P node", () -> { p2p.close(); return null; });
        }

        ok("close", () -> { node.close(); return null; });
        report();
    }

    /**
     * How long, in milliseconds, one document sync ran before it answered.
     * DefraDB keeps trying an unanswered sync (no peer has the document, or
     * the gossip mesh is still forming) until its timeout, so this is about
     * the timeout DefraDB was given. Fails if the sync is still running
     * after 30s.
     */
    static Long timedSync(Call sync) throws Exception {
        java.util.concurrent.CompletableFuture<Long> timed = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                sync.run();
            } catch (Exception unanswered) {
                // Expected: the sync gives up at its timeout.
            }
            return (System.nanoTime() - start) / 1_000_000;
        });
        return timed.get(30, TimeUnit.SECONDS);
    }

    static String docIDOf(String added) {
        return added == null ? "" : added.replaceAll("(?s).*\"(bae-[0-9a-f-]+)\".*", "$1");
    }

    static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    static String base58(byte[] bytes) {
        BigInteger value = new BigInteger(1, bytes);
        StringBuilder out = new StringBuilder();
        BigInteger base = BigInteger.valueOf(58);
        while (value.signum() > 0) {
            BigInteger[] divided = value.divideAndRemainder(base);
            out.append(BASE58.charAt(divided[1].intValue()));
            value = divided[0];
        }
        for (byte b : bytes) {
            if (b != 0) break;
            out.append('1');
        }
        return out.reverse().toString();
    }

    static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    /** A P-256 key pair, its compressed public key in hex, and its did:key. */
    static Object[] p256Identity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        byte[] compressed = new byte[33];
        compressed[0] = (byte) (pub.getW().getAffineY().testBit(0) ? 3 : 2);
        System.arraycopy(unsigned32(pub.getW().getAffineX()), 0, compressed, 1, 32);
        StringBuilder hex = new StringBuilder();
        for (byte b : compressed) hex.append(String.format("%02x", b));
        // DefraDB's did:key for a P-256 key: the multicodec prefix 0x1200 as a
        // varint, then the uncompressed point (0x04 || X || Y).
        byte[] multicodec = new byte[67];
        multicodec[0] = (byte) 0x80;
        multicodec[1] = (byte) 0x24;
        multicodec[2] = 0x04;
        System.arraycopy(unsigned32(pub.getW().getAffineX()), 0, multicodec, 3, 32);
        System.arraycopy(unsigned32(pub.getW().getAffineY()), 0, multicodec, 35, 32);
        return new Object[] { pair, hex.toString(), "did:key:z" + base58(multicodec) };
    }

    static List<String> addresses(String info) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("/ip4/127\\.0\\.0\\.1/tcp/\\d+/p2p/\\w+").matcher(info == null ? "" : info);
        while (m.find()) found.add(m.group());
        return found;
    }

    /** The calls the Rust C API added, which the Go-era SDK lacked. */
    static void rustAdditions(DefraNode node, DefraIdentity owner, DefraCollection collection) throws Exception {
        // Identities.
        String ownerDID = (String) ok("identityDID", () -> node.identityDID(owner));
        check("identityDID is a did:key", ownerDID != null && ownerDID.startsWith("did:key:"), String.valueOf(ownerDID));
        reaches("identityFromBearerToken", () -> node.identityFromBearerToken(ownerDID, "token"));
        ok("setNodeIdentity", () -> node.setNodeIdentity(owner));
        String nodeIdentity = (String) ok("getNodeIdentity after setNodeIdentity", node::getNodeIdentity);
        check("the node takes the identity", nodeIdentity != null && nodeIdentity.contains(String.valueOf(ownerDID)), String.valueOf(nodeIdentity));
        ok("setNodeIdentity null", () -> node.setNodeIdentity(null));

        // A remote identity: DefraDB signs through the Java signer.
        Object[] p256 = p256Identity();
        KeyPair pair = (KeyPair) p256[0];
        AtomicInteger signatures = new AtomicInteger();
        source.defra.DefraRemoteSigner signer = (did, keyType, payload) -> {
            signatures.incrementAndGet();
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(pair.getPrivate());
            signature.update(payload);
            return signature.sign();
        };
        DefraIdentity remote = (DefraIdentity) ok("identityRemote", () -> node.identityRemote((String) p256[2], "secp256r1", (String) p256[1], signer));
        ok("identityRemote again with the same signer", () -> node.identityRemote((String) p256[2], "secp256r1", (String) p256[1], signer));
        refuses("identityRemote with a mismatched DID", "mismatch", () -> node.identityRemote(ownerDID, "secp256r1", (String) p256[1], signer));
        if (remote != null) {
            DefraNodeInitOptions signed = new DefraNodeInitOptions();
            signed.inMemory = true;
            signed.identity = remote;
            DefraNode signing = (DefraNode) ok("new DefraNode with a remote identity", () -> new DefraNode(signed));
            if (signing != null) {
                ok("remote node addCollection", () -> signing.addCollection("type Note { text: String }"));
                DefraCollectionOptions notes = new DefraCollectionOptions();
                notes.name = "Note";
                ok("remote node addDocument", () -> signing.addDocument("{\"text\":\"signed\"}", false, "", notes));
                check("the Java signer signs the node's blocks", signatures.get() > 0, "no signature requested");
                ok("close remote node", () -> { signing.close(); return null; });
            }
        }

        // Saving many documents, and purging.
        ok("saveDocuments", () -> node.saveDocuments("[{\"name\":\"S1\"},{\"name\":\"S2\"}]", false, "", users()));
        String saved = (String) ok("query saved", () -> node.executeQuery("{ User { name } }", "", ""));
        check("saveDocuments adds both", saved != null && saved.contains("S1") && saved.contains("S2"), String.valueOf(saved));
        ok("collection saveDocuments", () -> collection.saveDocuments("[{\"name\":\"S3\"}]", false, "", null));
        String toPurge = docIDOf((String) ok("addDocument to purge", () -> node.addDocument("{\"name\":\"Purged\"}", false, "", users())));
        String purged = (String) ok("purgeDocuments", () -> node.purgeDocuments(new String[] { toPurge }, users()));
        check("purgeDocuments counts one", purged != null && purged.replace(" ", "").contains("\"Count\":1"), String.valueOf(purged));
        String afterPurge = (String) ok("query after purge", () -> node.executeQuery("{ User { name } }", "", ""));
        check("the purged document is gone", afterPurge != null && !afterPurge.contains("Purged"), String.valueOf(afterPurge));
        ok("collection purgeDocuments", () -> collection.purgeDocuments(new String[] { "bae-00000000-0000-0000-0000-000000000000" }, null));

        // Asynchronous queries, on the node and in a transaction.
        String async = (String) ok("executeQueryAsync", () -> node.executeQueryAsync("{ User { name } }", "", "", null).get(10, TimeUnit.SECONDS));
        check("executeQueryAsync answers", async != null && async.contains("S1"), String.valueOf(async));
        String asyncFiltered = (String) ok("executeQueryAsync with variables", () -> node.executeQueryAsync(
            "query Q($n: String) { User(filter: {name: {_eq: $n}}) { name } }", "Q", "{\"n\":\"S2\"}", null).get(10, TimeUnit.SECONDS));
        check("executeQueryAsync passes variables", asyncFiltered != null && asyncFiltered.contains("S2") && !asyncFiltered.contains("S1"), String.valueOf(asyncFiltered));
        ok("executeQueryAsync failure completes the future", () -> {
            try {
                node.executeQueryAsync("{ User { name } }", "", "not json", null).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof DefraException && e.getCause().getMessage() != null && !e.getCause().getMessage().isEmpty()) {
                    return null;
                }
                throw e;
            }
            throw new AssertionError("malformed variables were accepted");
        });
        DefraTransaction txn = (DefraTransaction) ok("transactionCreate for additions", () -> node.transactionCreate(false));
        ok("txn saveDocuments", () -> txn.saveDocuments("[{\"name\":\"T1\"}]", false, "", users(), null));
        String txnAsync = (String) ok("txn executeQueryAsync", () -> txn.executeQueryAsync("{ User { name } }", "", "", null).get(10, TimeUnit.SECONDS));
        check("txn executeQueryAsync sees its write", txnAsync != null && txnAsync.contains("T1"), String.valueOf(txnAsync));
        ok("txn purgeDocuments", () -> txn.purgeDocuments(new String[] { "bae-00000000-0000-0000-0000-000000000000" }, users(), null));
        reaches("txn batchStart", () -> txn.batchStart(null));
        reaches("txn batchSign", () -> txn.batchSign(null));
        ok("txn discard additions", () -> { txn.discard(); return null; });

        // Batch signing on the node.
        reaches("batchStart", () -> node.batchStart(owner));
        reaches("addDocument in a batch", () -> node.addDocument("{\"name\":\"Batched\"}", false, "", users(), owner));
        reaches("batchSign", () -> node.batchSign(owner));

        // Node events.
        CountDownLatch event = new CountDownLatch(1);
        String events = (String) ok("subscribeEvents", () -> node.subscribeEvents("*", e -> event.countDown(), null));
        ok("write for an event", () -> node.addDocument("{\"name\":\"Evented\"}", false, "", users()));
        check("subscribeEvents delivers an event", event.await(10, TimeUnit.SECONDS), "no event within 10s");
        ok("unsubscribe events", () -> { node.unsubscribe(events); return null; });

        // Profiling, absent from a default build.
        refuses("profilingStart", "profiling", () -> DefraNode.profilingStart(new File(System.getProperty("java.io.tmpdir"), "trace.json").getPath()));
        refuses("profilingStop", "profiling", DefraNode::profilingStop);

        // Node access control purge.
        DefraNodeInitOptions nacOptions = new DefraNodeInitOptions();
        nacOptions.inMemory = true;
        nacOptions.enableNodeACP = true;
        nacOptions.identity = owner;
        DefraNode nacNode = (DefraNode) ok("new DefraNode with NAC for purge", () -> new DefraNode(nacOptions));
        if (nacNode != null) {
            ok("ACPPurgeNAC", () -> nacNode.ACPPurgeNAC(owner));
            ok("close NAC purge node", () -> { nacNode.close(); return null; });
        }

        // Replication through a filter, P2P extras and the mobile calls.
        AtomicInteger asked = new AtomicInteger();
        DefraNodeInitOptions receiverOptions = new DefraNodeInitOptions();
        receiverOptions.inMemory = true;
        receiverOptions.disableP2P = false;
        receiverOptions.listeningAddresses = "/ip4/127.0.0.1/tcp/0";
        receiverOptions.replicationFilter = (collectionID, collectionName, docID, fieldsJSON) -> {
            asked.incrementAndGet();
            return !fieldsJSON.contains("skip");
        };
        DefraNodeInitOptions senderOptions = new DefraNodeInitOptions();
        senderOptions.inMemory = true;
        senderOptions.disableP2P = false;
        senderOptions.listeningAddresses = "/ip4/127.0.0.1/tcp/0";
        DefraNode receiver = (DefraNode) ok("new receiver with a replication filter", () -> new DefraNode(receiverOptions));
        DefraNode sender = (DefraNode) ok("new sender", () -> new DefraNode(senderOptions));
        if (receiver != null && sender != null) {
            DefraCollectionOptions items = new DefraCollectionOptions();
            items.name = "Item";
            ok("receiver addCollection", () -> receiver.addCollection("type Item { name: String }"));
            ok("sender addCollection", () -> sender.addCollection("type Item { name: String }"));
            List<String> receiverAddresses = addresses((String) ok("receiver getP2PInfo", receiver::getP2PInfo));
            check("the receiver listens", !receiverAddresses.isEmpty(), "no address");
            ok("addP2PReplicator to the receiver", () -> sender.addP2PReplicator("Item", String.join(",", receiverAddresses)));
            ok("sender adds a kept document", () -> sender.addDocument("{\"name\":\"keep\"}", false, "", items));
            ok("sender adds a refused document", () -> sender.addDocument("{\"name\":\"skip\"}", false, "", items));
            String received = "";
            for (int i = 0; i < 100 && !received.contains("keep"); i++) {
                Thread.sleep(200);
                received = receiver.executeQuery("{ Item { name } }", "", "");
            }
            Thread.sleep(1000);
            String finalReceived = receiver.executeQuery("{ Item { name } }", "", "");
            check("the filter keeps a matching document", finalReceived.contains("keep"), finalReceived);
            check("the filter refuses the other", !finalReceived.contains("skip"), finalReceived);
            check("the filter was asked", asked.get() > 0, "never asked");

            // A sync between connected peers ends by its timeout: a document
            // the receiver asks for that no peer answers runs until then, so
            // a timeout DefraDB did not get (0 is no limit) would hang here.
            ok("receiver addCollection Pulled", () -> receiver.addCollection("type Pulled { name: String }"));
            ok("sender addCollection Pulled", () -> sender.addCollection("type Pulled { name: String }"));
            List<String> senderAddresses = addresses((String) ok("sender getP2PInfo", sender::getP2PInfo));
            ok("sender addP2PCollection Pulled", () -> sender.addP2PCollection("Pulled"));
            ok("receiver addP2PCollection Pulled", () -> receiver.addP2PCollection("Pulled"));
            ok("receiver connects to the sender", () -> receiver.connectP2PPeers(String.join(",", senderAddresses)));
            String unknown = "bae-00000000-0000-0000-0000-000000000000";
            Long twoSeconds = (Long) ok("syncP2PDocuments with a 2s timeout", () -> timedSync(() -> receiver.syncP2PDocuments("Pulled", unknown, 2000L)));
            check("a 2s sync ends near 2s", twoSeconds != null && twoSeconds >= 1_500 && twoSeconds < 10_000, twoSeconds + "ms");
            Long underOne = (Long) ok("syncP2PDocuments with a 500us Go timeout", () -> timedSync(() -> receiver.syncP2PDocuments("Pulled", unknown, "500us")));
            check("a sub-millisecond sync is not unlimited", underOne != null && underOne < 1_500, underOne + "ms");

            ok("retryP2PReplicators", () -> sender.retryP2PReplicators(null));
            reaches("addP2PReplicator filtered", () -> sender.addP2PReplicator("Item", String.join(",", receiverAddresses), "{\"Item\": {\"name\": {\"_eq\": \"keep\"}}}", null));
            reaches("getShareableAddress", () -> sender.getShareableAddress(null));
            reaches("getSyncStatus", () -> sender.getSyncStatus(null));
            reaches("notifyNetworkChange", () -> sender.notifyNetworkChange(null));
            String receiverPeer = receiverAddresses.isEmpty() ? "" : receiverAddresses.get(0).replaceAll(".*/p2p/", "");
            reaches("getPeerDetails", () -> sender.getPeerDetails(receiverPeer, null));
        }
        if (sender != null) ok("close sender", () -> { sender.close(); return null; });
        if (receiver != null) ok("close receiver", () -> { receiver.close(); return null; });
    }

    static void report() {
        for (String failure : failures) {
            System.err.println("FAIL " + failure);
        }
        System.out.println(passed + " passed, " + failures.size() + " failed");
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
