package network;

import com.google.gson.Gson;
import crypto.Ed25519Util;
import model.*;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * NetworkEngine — Component 1: The P2P Layer.
 *
 * Integration Plan §2 (Component 1: The Network Engine):
 *  - Runs on a dedicated background thread pool.
 *  - Handles all raw socket I/O, routing, and cheap Sybil defenses.
 *  - Does NOT do VRF math — all heavy crypto is deferred to the ConsensusEngine.
 *  - P2P Transaction Gossiping is REMOVED — transactions flow via SharedBuffer only.
 *
 * Responsibilities:
 *  1. Accept incoming TCP connections from peers.
 *  2. Execute the full ingressLoop pipeline on every incoming message.
 *  3. Assemble BlockCertificates from incoming CERTIFY_VOTE messages.
 *  4. Fire SystemInterrupt(CertificateEvent) to the ConsensusEngine when ready.
 *  5. Gossip messages to all connected peers.
 *  6. Active-fetch missing blocks for synchronization.
 */
public class NetworkEngine {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Expected committee size for voters (Phase 3 + Phase 6 threshold calculation). */
    private static final int  EXPECTED_VOTER_COMMITTEE = 1000;

    /** Certificate finalisation threshold: >68% of expected committee. */
    private static final int  CERT_THRESHOLD = (int)(0.68 * EXPECTED_VOTER_COMMITTEE); // 680

    /** Maximum rounds ahead of the Network_Tip to buffer messages (10-Block Rule). */
    private static final int  MAX_FUTURE_ROUNDS = 10;

    /** LRU cache size for deduplicating seen messages. */
    private static final int  SEEN_CACHE_SIZE = 50_000;

    // -------------------------------------------------------------------------
    // State Variables (§2.1)
    // -------------------------------------------------------------------------

    /** Stores SHA-256(message) to prevent infinite routing loops. */
    private final LRUCache<String> seenMessages = new LRUCache<>(SEEN_CACHE_SIZE);

    /** The highest consensus round observed in the gossip stream. */
    private volatile long networkTip = 0L;

    /** The live message buffer for the ConsensusEngine. */
    private final ForwardCache forwardCache = new ForwardCache();

    /** DDoS protection: Token Bucket rate limiter per IP. */
    private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter();

    /**
     * Equivocation guard: tracks which public key proposed in each round.
     * Ensures each round has at most one valid proposer per sender.
     * Round -> SenderPubKey
     */
    private final ConcurrentHashMap<Long, String> proposerEquivocationGuard = new ConcurrentHashMap<>();

    /**
     * Certificate assembler: accumulates CERTIFY_VOTEs per round per blockHash.
     * Round -> BlockHash -> In-progress BlockCertificate
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, BlockCertificate>> certAccumulator
        = new ConcurrentHashMap<>();

    /** Connected peer sockets for gossip. */
    private final List<PrintWriter> peerWriters = new CopyOnWriteArrayList<>();

    /** Proposer ban list: banned public keys for submitting invalid blocks in Phase 2. */
    private final Set<String> bannedProposers = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------
    // External Dependencies (injected)
    // -------------------------------------------------------------------------

    /** This node's Ed25519 identity (for signing outgoing messages). */
    private final String myPubKeyBase64;
    private final Ed25519PrivateKeyParameters myPrivateKey;

    /** Port this node listens on for incoming connections. */
    private final int listenPort;

    /** Seed peer addresses to connect to on startup. */
    private final List<String> seedPeers; // "host:port" strings

    /** Queued certificates — ConsensusEngine blocks on this in Phase 6. */
    private final LinkedBlockingQueue<BlockCertificate> certificateQueue;

    /** Reference to the SharedBuffer for certificate event notification. */
    private volatile buffer.SharedBuffer sharedBuffer;

    /** Thread pool for handling peer I/O. */
    private final ExecutorService ioThreadPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "NetworkIO");
        t.setDaemon(true);
        return t;
    });

    private static final Gson GSON = new Gson();
    private volatile boolean running = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public NetworkEngine(int listenPort,
                         String myPubKeyBase64,
                         Ed25519PrivateKeyParameters myPrivateKey,
                         List<String> seedPeers,
                         LinkedBlockingQueue<BlockCertificate> certificateQueue) {
        this.listenPort       = listenPort;
        this.myPubKeyBase64   = myPubKeyBase64;
        this.myPrivateKey     = myPrivateKey;
        this.seedPeers        = seedPeers;
        this.certificateQueue = certificateQueue;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the NetworkEngine: launches server + connects to seed peers.
     *
     * Integration Plan §2.2 start():
     *  Establishes persistent connections to 4-8 Relay Nodes.
     *  Continuously monitors connection health.
     */
    public void start() {
        running = true;
        // Start TCP server to accept incoming connections
        ioThreadPool.submit(this::runServer);
        // Connect to all configured seed peers
        for (String peer : seedPeers) {
            ioThreadPool.submit(() -> connectToPeer(peer));
        }
        System.out.println("[NetworkEngine] Started on port " + listenPort +
                           " | Seeds: " + seedPeers);
    }

    public void stop() {
        running = false;
        ioThreadPool.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // TCP Server
    // -------------------------------------------------------------------------

    private void runServer() {
        try (ServerSocket server = new ServerSocket(listenPort)) {
            System.out.println("[NetworkEngine] Listening on :" + listenPort);
            while (running) {
                Socket client = server.accept();
                ioThreadPool.submit(() -> handlePeer(client));
            }
        } catch (Exception e) {
            if (running) System.err.println("[NetworkEngine] Server error: " + e.getMessage());
        }
    }

    private void connectToPeer(String hostPort) {
        String[] parts = hostPort.split(":");
        if (parts.length != 2) return;
        String host = parts[0];
        int    port;
        try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return; }

        int retries = 0;
        while (running && retries < 5) {
            try {
                Socket socket = new Socket(host, port);
                handlePeer(socket);
                return;
            } catch (Exception e) {
                retries++;
                try { Thread.sleep(2000L * retries); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void handlePeer(Socket socket) {
        String remoteIp = socket.getInetAddress().getHostAddress();
        try {
            PrintWriter    writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            peerWriters.add(writer);
            System.out.println("[NetworkEngine] Connected: " + remoteIp + ":" + socket.getPort());

            String line;
            while (running && (line = reader.readLine()) != null) {
                final String rawLine = line;
                final String ip = remoteIp;
                ioThreadPool.submit(() -> ingressLoop(rawLine.getBytes(), ip));
            }
        } catch (Exception e) {
            // Connection dropped — normal occurrence
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // ingressLoop — The Full 6-Step Pipeline (§2.2)
    // -------------------------------------------------------------------------

    /**
     * Processes every incoming raw payload through the full multi-stage pipeline.
     *
     * Integration Plan §2.2 ingressLoop:
     *  Step 0: DDoS Protection — Token Bucket rate limit check.
     *  Step 1: Payload Hash  — Duplicate message detection via LRU.
     *  Step 2: Cryptography  — Cheap Ed25519 signature verification.
     *  Step 3: Equivocation  — Drop duplicate proposals from same sender in same round.
     *  Step 4: Halt Interrupt — Certificate messages trigger the SystemInterrupt.
     *  Step 5: 10-Block Rule  — Drop messages more than 10 rounds ahead of tip.
     *  Step 6: Route          — Push valid VoteMessages to ForwardCache.
     */
    public void ingressLoop(byte[] rawPayload, String senderIp) {
        // ── Step 0: DDoS Protection ──────────────────────────────────────────
        if (!rateLimiter.allow(senderIp)) {
            return; // DROP — IP is rate-limited or banned
        }

        // ── Step 1: Payload Hash (Deduplication) ─────────────────────────────
        String messageId = Ed25519Util.sha256Hex(rawPayload);
        if (seenMessages.contains(messageId)) {
            return; // DROP — already processed this message
        }
        seenMessages.add(messageId);

        // ── Parse the NetworkMessage envelope ────────────────────────────────
        NetworkMessage msg;
        try {
            msg = NetworkMessage.fromJson(new String(rawPayload));
        } catch (Exception e) {
            return; // DROP — malformed JSON
        }

        if (msg.getType() == null || msg.getSenderPubKey() == null) {
            return; // DROP — missing required envelope fields
        }

        // ── Step 2: Cryptography — Fast Ed25519 signature check ──────────────
        boolean sigValid = Ed25519Util.verifyFromBase64(
            msg.getSenderPubKey(),
            msg.getSignableData(),
            msg.getSignature()
        );
        if (!sigValid) {
            rateLimiter.ban(senderIp); // Ban IP for sending forged message
            return; // DROP — invalid signature
        }

        // ── Step 3: Proposer Equivocation Defense ────────────────────────────
        if (msg.getType() == NetworkMessage.Type.PROPOSAL) {
            String existingProposer = proposerEquivocationGuard.putIfAbsent(
                msg.getRound(), msg.getSenderPubKey()
            );
            if (existingProposer != null && !existingProposer.equals(msg.getSenderPubKey())) {
                // A DIFFERENT sender already proposed this round — this is equivocation!
                System.out.println("[NetworkEngine] Equivocation detected! Round=" + msg.getRound() +
                                   " existing=" + existingProposer.substring(0, 8) +
                                   " new=" + msg.getSenderPubKey().substring(0, 8));
                return; // DROP
            }
        }

        // ── Step 4: Halting Interrupt — Certificate detection ────────────────
        if (msg.getType() == NetworkMessage.Type.BLOCK_CERTIFICATE) {
            try {
                BlockCertificate cert = BlockCertificate.fromJson(msg.getPayload());
                System.out.println("[NetworkEngine] Certificate received: " + cert);
                // Update network tip
                if (cert.getRound() > networkTip) networkTip = cert.getRound();
                // Fire SystemInterrupt(CertificateEvent) — unblocks ConsensusEngine Phase 6
                certificateQueue.offer(cert);
                // Notify SharedBuffer's Active Listener
                if (sharedBuffer != null) sharedBuffer.onCertificateEvent(cert);
                // Gossip to other peers
                gossipRaw(rawPayload);
            } catch (Exception e) {
                System.err.println("[NetworkEngine] Failed to parse certificate: " + e.getMessage());
            }
            return;
        }

        // ── Step 5: 10-Block Rule ─────────────────────────────────────────────
        if (msg.getRound() > networkTip + MAX_FUTURE_ROUNDS) {
            return; // DROP — too far in the future; prevents cache flooding
        }

        // Update network tip passively
        if (msg.getRound() > networkTip) {
            networkTip = msg.getRound();
        }

        // ── Step 6: Route to ForwardCache ─────────────────────────────────────
        VoteMessage.Step voteStep = resolveStep(msg.getType());
        if (voteStep != null && msg.getPayload() != null) {
            try {
                VoteMessage vote = VoteMessage.fromJson(msg.getPayload());
                // Check if proposer is banned (from Phase 2 bad block simulation)
                if (bannedProposers.contains(vote.getSenderPubKey())) {
                    return; // DROP — banned proposer
                }
                forwardCache.put(msg.getRound(), voteStep, vote);
            } catch (Exception e) {
                System.err.println("[NetworkEngine] Failed to parse VoteMessage: " + e.getMessage());
                return;
            }
        }

        // Handle PROPOSAL separately — store in ForwardCache under SOFT_VOTE slot
        // so Phase 2 of ConsensusEngine can retrieve it
        if (msg.getType() == NetworkMessage.Type.PROPOSAL && msg.getPayload() != null) {
            try {
                Block proposed = Block.fromJson(msg.getPayload());
                // Store as a special proposal: wrap block hash in a VoteMessage-like structure
                VoteMessage proposalVote = new VoteMessage();
                proposalVote.setRound(msg.getRound());
                proposalVote.setStep(VoteMessage.Step.SOFT_VOTE);
                proposalVote.setSenderPubKey(msg.getSenderPubKey());
                proposalVote.setChoice(proposed.getHash());
                proposalVote.setVrfProof(proposed.getHeader() != null ? proposed.getHeader().getProposerVRFProof() : "");
                proposalVote.setEd25519Signature(msg.getSignature());
                forwardCache.put(msg.getRound(), VoteMessage.Step.SOFT_VOTE, proposalVote);
            } catch (Exception e) {
                System.err.println("[NetworkEngine] Failed to parse Proposal block: " + e.getMessage());
            }
        }

        // Handle CERTIFY_VOTE certificate assembly in background
        if (msg.getType() == NetworkMessage.Type.CERTIFY_VOTE && msg.getPayload() != null) {
            assembleCertificate(msg);
        }

        // Gossip valid message to all other connected peers
        gossipRaw(rawPayload);
    }

    // -------------------------------------------------------------------------
    // Certificate Assembly (Background — §5.2 Phase 6)
    // -------------------------------------------------------------------------

    /**
     * Accumulates CERTIFY_VOTE messages and assembles the BlockCertificate.
     *
     * Integration Plan §5.2 Phase 6:
     *  When the NetworkEngine independently collects >2/3 weight of valid
     *  CertifyVote signatures for a single hash, it stitches them together
     *  into the final BlockCertificate and broadcasts it globally.
     */
    private void assembleCertificate(NetworkMessage msg) {
        try {
            VoteMessage vote = VoteMessage.fromJson(msg.getPayload());
            long  round      = vote.getRound();
            String blockHash = vote.getChoice();
            int    weight    = vote.getSortitionWeight();

            if (weight <= 0 || blockHash == null) return;

            // Get or create certificate for this round+hash
            BlockCertificate cert = certAccumulator
                .computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                .computeIfAbsent(blockHash, h -> {
                    BlockCertificate c = new BlockCertificate();
                    c.setRound(round);
                    c.setBlockHash(h);
                    return c;
                });

            synchronized (cert) {
                cert.addVote(vote.getSenderPubKey(), vote.getVrfProof(), weight, vote.getEd25519Signature());

                // Check if we have reached the 68% supermajority threshold
                if (cert.isFinal(EXPECTED_VOTER_COMMITTEE)) {
                    System.out.println("[NetworkEngine] Certificate COMPLETE! Round=" + round +
                                       " hash=" + blockHash.substring(0, 8) +
                                       " weight=" + cert.getTotalWeight());

                    // Broadcast the complete certificate globally
                    gossip(buildEnvelope(NetworkMessage.Type.BLOCK_CERTIFICATE, round, cert.toJson()));

                    // Fire SystemInterrupt(CertificateEvent) — unblocks ConsensusEngine
                    certificateQueue.offer(cert);

                    // Notify SharedBuffer Active Listener
                    if (sharedBuffer != null) sharedBuffer.onCertificateEvent(cert);

                    // Cleanup assembler for this round
                    certAccumulator.remove(round);
                }
            }
        } catch (Exception e) {
            System.err.println("[NetworkEngine] Certificate assembly error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Gossip (§2.2)
    // -------------------------------------------------------------------------

    /**
     * Signs and broadcasts a message to all connected peers.
     *
     * Integration Plan §2.2 gossip():
     *  Serializes, signs, and pushes the message to all connected Relay Nodes.
     */
    public void gossip(NetworkMessage msg) {
        // Sign the envelope with our private key
        String signature = Ed25519Util.signToBase64(myPrivateKey, msg.getSignableData());
        msg.setSenderPubKey(myPubKeyBase64);
        msg.setSignature(signature);
        gossipRaw(msg.toJson().getBytes());
    }

    /** Gossips pre-serialized raw bytes (for relaying received messages). */
    private void gossipRaw(byte[] rawPayload) {
        String json = new String(rawPayload);
        List<PrintWriter> dead = new ArrayList<>();
        for (PrintWriter writer : peerWriters) {
            try {
                writer.println(json);
                if (writer.checkError()) dead.add(writer);
            } catch (Exception e) {
                dead.add(writer);
            }
        }
        peerWriters.removeAll(dead);
    }

    /** Builds a signed NetworkMessage envelope. */
    public NetworkMessage buildEnvelope(NetworkMessage.Type type, long round, String payload) {
        NetworkMessage msg = new NetworkMessage();
        msg.setType(type);
        msg.setRound(round);
        msg.setSenderPubKey(myPubKeyBase64);
        msg.setPayload(payload);
        String sig = Ed25519Util.signToBase64(myPrivateKey, msg.getSignableData());
        msg.setSignature(sig);
        return msg;
    }

    // -------------------------------------------------------------------------
    // Active Fetch (§2.2)
    // -------------------------------------------------------------------------

    /**
     * Requests specific block range from connected peers for synchronization.
     *
     * Integration Plan §2.2 activeFetch():
     *  Sends direct sync requests to Relays to download missing block payloads.
     *  Used during Catchup (handleCatchup) and Phase 6 payload retrieval.
     *
     * @param startRound First round to fetch (inclusive).
     * @param endRound   Last round to fetch (inclusive).
     * @return List of fetched blocks in ascending round order.
     */
    public List<Block> activeFetch(long startRound, long endRound) {
        List<Block> blocks = new ArrayList<>();
        // Build and send SYNC_REQUEST
        NetworkMessage req = buildEnvelope(NetworkMessage.Type.SYNC_REQUEST, startRound,
                                            startRound + ":" + endRound);
        gossip(req);
        // In a real implementation, we'd wait for SYNC_RESPONSE messages.
        // For the testbed, we return empty and let the node retry via the retry loop in Phase 6.
        System.out.println("[NetworkEngine] activeFetch requested rounds " + startRound + " to " + endRound);
        return blocks;
    }

    // -------------------------------------------------------------------------
    // Proposer Banning (called by StateEngine during Phase 2 simulation fail)
    // -------------------------------------------------------------------------

    /**
     * Bans a proposer's public key for submitting an invalid block.
     *
     * Integration Plan §5.2 Phase 2:
     *  If a proposer's block fails simulation, ban the ProposerPubKey and IP.
     */
    public void banProposer(String proposerPubKey) {
        bannedProposers.add(proposerPubKey);
        System.out.println("[NetworkEngine] Banned proposer: " + proposerPubKey.substring(0, 8));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private VoteMessage.Step resolveStep(NetworkMessage.Type type) {
        return switch (type) {
            case SOFT_VOTE    -> VoteMessage.Step.SOFT_VOTE;
            case BBA_GOSSIP   -> VoteMessage.Step.BBA_GOSSIP;
            case COMMON_COIN  -> VoteMessage.Step.COMMON_COIN;
            case CERTIFY_VOTE -> VoteMessage.Step.CERTIFY_VOTE;
            default           -> null;
        };
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public ForwardCache   getForwardCache()                      { return forwardCache; }
    public long           getNetworkTip()                        { return networkTip; }
    public void           setNetworkTip(long tip)               { this.networkTip = tip; }
    public void           setSharedBuffer(buffer.SharedBuffer sb){ this.sharedBuffer = sb; }
}
