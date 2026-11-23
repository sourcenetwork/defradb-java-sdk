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
 * Configuration used to create a {@link DefraNode}.
 *
 * <p>The fields mirror DefraDB's node, store, database, P2P, HTTP, document
 * ACP, and node ACP options. Duration fields use milliseconds.</p>
 */
public class DefraNodeInitOptions {

    /** Filesystem path for the database store. */
    public String dbPath;
    /** Comma-separated P2P listen addresses. */
    public String listeningAddresses;
    /** Comma-separated transaction retry intervals. */
    public String replicatorRetryIntervals;
    /** Comma-separated bootstrap peer addresses. */
    public String peers;
    /** Identity used by the node, or {@code null} if unspecified. */
    public DefraIdentity identity;
    /** Whether the database store runs in memory. */
    public boolean inMemory;
    /** Whether P2P networking is disabled. */
    public boolean disableP2P;
    /**
     * Has no effect: the embedded node does not serve the HTTP API.
     *
     * @deprecated the embedded node never starts the HTTP API server
     */
    @Deprecated
    public boolean disableAPI;
    /** Whether node access control is enabled. */
    public boolean enableNodeACP;
    /** Maximum number of retries per transaction; zero uses the DefraDB default. */
    public int maxTransactionRetries;
    /** Store implementation name, such as {@code badger} or {@code memory}. */
    public String storeType;
    /** Maximum Badger value-log file size in bytes. */
    public long badgerFileSize;
    /** Badger encryption key, or {@code null} if encryption is disabled. */
    public byte[] badgerEncryptionKey;
    /**
     * Has no effect: blocks are signed by default.
     *
     * @deprecated use {@link #disableSigning} to turn block signing off
     */
    @Deprecated
    public boolean enableSigning;
    /** Key used for searchable encryption, or {@code null} if disabled. */
    public byte[] searchableEncryptionKey;
    /** Timeout in milliseconds for synchronizing block links over P2P. */
    public long p2pBlockSyncTimeoutMs;
    /** Number of lens runtime instances in the pool. */
    public int lensPoolSize;
    /** Blockstore chunk size; zero uses the DefraDB default. */
    public int chunkSize;
    /** Whether the P2P PubSub system is enabled. */
    public boolean enablePubSub;
    /** Whether the P2P relay system is enabled. */
    public boolean enableRelay;
    /** Whether connection backoff is cleared before retrying. */
    public boolean enableClearBackoffOnRetry;
    /** Private key for the P2P node, or {@code null} to generate one. */
    public byte[] p2pPrivateKey;
    /** Address on which the HTTP API listens. */
    public String httpAddress;
    /** Comma-separated origins allowed by HTTP CORS handling. */
    public String httpAllowedOrigins;
    /** Path to the HTTP TLS certificate. */
    public String tlsCertPath;
    /** Path to the HTTP TLS private key. */
    public String tlsKeyPath;
    /** HTTP connection read timeout in milliseconds. */
    public long httpReadTimeoutMs;
    /** HTTP connection write timeout in milliseconds. */
    public long httpWriteTimeoutMs;
    /** HTTP connection idle timeout in milliseconds. */
    public long httpIdleTimeoutMs;
    /** Document ACP implementation: {@code none}, {@code local}, or {@code remote}. */
    public String documentACPType;
    /** Filesystem path for the document ACP system. */
    public String documentACPPath;
    /** Log identifier used by remote document ACP. */
    public String remoteDACLogID;
    /** gRPC address used by remote document ACP. */
    public String remoteDACGRPCAddress;
    /** Comet RPC address used by remote document ACP. */
    public String remoteDACCometRPCAddress;
    /** Filesystem path for the node ACP system. */
    public String nodeACPPath;
    /** Run without signing blocks. Signing is on by default, with the node identity or a generated key. */
    public boolean disableSigning;
    /** Vera chain ID, for example "vera-test". */
    public String veraChainID;
    /** Raw 32-byte secp256k1 private key that signs this node's Vera transactions; null for none. */
    public byte[] veraSignerKey;
    /** P2P transport: "libp2p" (the default) or "iroh". */
    public String p2pTransport;
    /** Iroh relay URL; empty for none. */
    public String irohRelayURL;
    /** Iroh relay mode: "default", "disabled" or "custom"; empty for the default. */
    public String irohRelayMode;
    /** Relay URLs for the "custom" iroh relay mode, as a JSON array of strings. */
    public String irohRelayURLsJSON;
    /** Address the iroh endpoint binds to, for example "127.0.0.1"; empty for any. */
    public String irohBindAddress;
    /** UDP port the iroh endpoint binds to; 0 lets the system pick. */
    public int irohBindPort;
    /** Run iroh without peer discovery. */
    public boolean irohDisableDiscovery;
    /** DNS origin domain for iroh discovery; empty for the default. */
    public String irohDiscoveryOriginDomain;
    /** Pkarr relay URL iroh discovery publishes to; empty for the default. */
    public String irohPkarrRelayURL;
    /** An iroh key file from before the node shared one peer key across transports, imported when the store has none. */
    public String irohKeyPath;
    /** Most DAG fetches in flight at once; 0 for the default. */
    public long maxConcurrentDAGFetches;
    /** Most pushes to peers in flight at once; 0 for the default. */
    public long maxConcurrentPushTasks;
    /** Most document IDs one document sync request may name; 0 for the default (1000). */
    public long maxDocSyncRequestDocIDs;
    /** Per-peer rate limit burst; 0 for the default. */
    public int rateLimitBurst;
    /** Per-peer rate limit refill rate, in tokens per second; 0 for the default (50). */
    public double rateLimitRate;
    /** Decides which replicated documents the node keeps; null keeps them all. */
    public DefraReplicationFilter replicationFilter;
    /** Memory the libp2p resource manager allows, in MiB; 0 for the default. */
    public long p2pResourceMemoryMiB;
    /** File descriptors the libp2p resource manager allows, used only with {@link #p2pResourceMemoryMiB}; 0 for the default. */
    public long p2pResourceFileDescriptors;
    /** Most streams one peer may hold open; 0 for the default. */
    public long p2pMaxStreamsPerPeer;
    /** Run libp2p dial-only: it connects out but accepts no connections. Refused together with {@link #listeningAddresses}. */
    public boolean disableP2PListen;

    /** Creates options initialized to the native client's default values. */
    public DefraNodeInitOptions() {
        this.dbPath = "";
        this.listeningAddresses = "";
        this.replicatorRetryIntervals = "";
        this.peers = "";
        this.identity = null;
        this.inMemory = false;
        this.disableP2P = true;
        this.disableAPI = true;
        this.enableNodeACP = false;
        this.maxTransactionRetries = 0;
        this.storeType = "";
        this.badgerFileSize = 0;
        this.badgerEncryptionKey = null;
        this.enableSigning = false;
        this.searchableEncryptionKey = null;
        this.p2pBlockSyncTimeoutMs = 0;
        this.lensPoolSize = 0;
        this.chunkSize = 0;
        this.enablePubSub = false;
        this.enableRelay = false;
        this.enableClearBackoffOnRetry = false;
        this.p2pPrivateKey = null;
        this.httpAddress = "";
        this.httpAllowedOrigins = "";
        this.tlsCertPath = "";
        this.tlsKeyPath = "";
        this.httpReadTimeoutMs = 0;
        this.httpWriteTimeoutMs = 0;
        this.httpIdleTimeoutMs = 0;
        this.documentACPType = "";
        this.documentACPPath = "";
        this.remoteDACLogID = "";
        this.remoteDACGRPCAddress = "";
        this.remoteDACCometRPCAddress = "";
        this.nodeACPPath = "";
        this.veraChainID = "";
        this.p2pTransport = "";
        this.irohRelayURL = "";
        this.irohRelayMode = "";
        this.irohRelayURLsJSON = "";
        this.irohBindAddress = "";
        this.irohDiscoveryOriginDomain = "";
        this.irohPkarrRelayURL = "";
        this.irohKeyPath = "";
    }
    
}
