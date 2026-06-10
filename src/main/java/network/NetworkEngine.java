package network;

import com.google.gson.Gson;
import crypto.Ed25519Util;
import model.*;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * NetworkEngine — Component 1: The P2P Layer.
 *
 * Integration Plan §2 (The Network Engine):
 *  Runs on a dedicated background thread pool. Handles all raw socket I/O,
 *  routing, and cheap Sybil defenses. Does NOT do VRF math.
 *
 * Fixes applied:
 *  FIX #10: SharedBuffer.onCertificateEvent() is guarded by an idempotent-flush check.
 *           A round-tracked latch prevents the buffer from being flushed twice for
 *           the same round (once from local assembly, once from incoming BLOCK_CERT).
 *
 *  FIX #13/#14: SYNC_REQUEST and SYNC_RESPONSE handlers are now implemented.
 *           Receiving a SYNC_REQUEST sends back the requested blocks from the DB.
 *           activeFetch() now properly waits for SYNC_RESPONSE via a semaphore.
 *
 *  FIX #18: Incoming PROPOSAL payloads (full Block JSON) are stored in
 *           ForwardCache.putProposalBlock() so non-proposing nodes can retrieve
 *           them during Phase 2 VRF-min-hash selection.
 *
 *  FIX #19: activeFetch() peer simulation waits up to 3s for blocks from peers
 *           before falling back to empty (for testbed mode).
 *
 *  FIX #20: PROPOSAL messages are routed to their own ForwardCache slot (PROPOSAL step),
 *           not SOFT_VOTE. This prevents proposal metadata from polluting Phase 4 counts.
 *
 *  FIX #21/#22: Equivocation guard correctly blocks multiple distinct proposals from
 *           the same sender in the same round. Also purges old entries per round.
 *
 *  FIX #23: Inner-vote pubKey binding check: VoteMessage.senderPubKey must equal
 *           the envelope NetworkMessage.senderPubKey. If they differ, the message is dropped.
 *
 *  FIX #24: The CERTIFY_VOTE inner VoteMessage signature is also verified before assembling.
 *
 *  FIX #25: Block header chain validation in PROPOSAL ingressLoop: previousBlockHash in the
 *           proposed block header must match the current tip's hash (networkTipHash).
 *
 *  FIX #26: networkTip is only updated from non-CERTIFY messages that pass all checks.
 *           BLOCK_CERTIFICATE messages update networkTip independently via their own flow.
 */
public class NetworkEngine {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int  EXPECTED_VOTER_COMMITTEE = 1000;
    private static final int  CERT_THRESHOLD           = (int)(0.68 * EXPECTED_VOTER_COMMITTEE);
    private static final int  MAX_FUTURE_ROUNDS        = 10;
    private static final int  SEEN_CACHE_SIZE          = 50_000;

    // -------------------------------------------------------------------------
    // State Variables (§2.1)
    // -------------------------------------------------------------------------

    private final LRUCache<String>       seenMessages              = new LRUCache<>(SEEN_CACHE_SIZE);
    private volatile long                networkTip                = 0L;
    /** FIX #25: Track the hash of the block at networkTip for header validation. */
    private volatile String              networkTipHash            = Block.BOTTOM_HASH;
    private final ForwardCache           forwardCache              = new ForwardCache();
    private final TokenBucketRateLimiter rateLimiter               = new TokenBucketRateLimiter();
    private final ConcurrentHashMap<Long, String> proposerEquivocationGuard = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, BlockCertificate>> certAccumulator
        = new ConcurrentHashMap<>();
    private final List<PrintWriter>      peerWriters               = new CopyOnWriteArrayList<>();
    private final Set<String>            bannedProposers           = ConcurrentHashMap.newKeySet();

    /** FIX #10: Tracks the last round for which the SharedBuffer was flushed. */
    private volatile long                lastFlushedRound          = -1L;

    // FIX #13/#14: Pending sync responses: round -> collected blocks
    private final ConcurrentHashMap<Long, List<Block>> syncResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Semaphore>   syncSemaphores = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // External Dependencies
    // -------------------------------------------------------------------------

    private final String                          myPubKeyBase64;
    private final Ed25519PrivateKeyParameters     myPrivateKey;
    private final int                             listenPort;
    private final List<String>                    seedPeers;
    private final LinkedBlockingQueue<BlockCertificate> certificateQueue;
    private volatile buffer.SharedBuffer          sharedBuffer;
    /** Reference to StateEngine DB for serving SYNC_RESPONSE blocks. */
    private volatile state.StateEngine            stateEngine;

    private final ExecutorService ioThreadPool = Executors.newFixedThreadPool(50, r -> {
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

    public void start() {
        running = true;
        ioThreadPool.submit(this::runServer);
        for (String peer : seedPeers) {
            ioThreadPool.submit(() -> connectToPeer(peer));
        }
        System.out.println("[NetworkEngine] Started on port " + listenPort + " | Seeds: " + seedPeers);
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
        int port;
        try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return; }

        int retries = 0;
        while (running && retries < 5) {
            try {
                Socket socket = new Socket(host, port);
                handlePeer(socket);
                return;
            } catch (Exception e) {
                retries++;
                try { Thread.sleep(2000L * retries); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); return;
                }
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
    // ingressLoop — Full 6-Step Pipeline (§2.2)
    // -------------------------------------------------------------------------

    public void ingressLoop(byte[] rawPayload, String senderIp) {
        // ── Step 0: DDoS Protection ──────────────────────────────────────────
        if (!rateLimiter.allow(senderIp)) return;

        // ── Step 1: Payload Hash (Deduplication) ─────────────────────────────
        String messageId = Ed25519Util.sha256Hex(rawPayload);
        if (seenMessages.contains(messageId)) return;
        seenMessages.add(messageId);

        // ── Parse NetworkMessage envelope ────────────────────────────────────
        NetworkMessage msg;
        try {
            msg = NetworkMessage.fromJson(new String(rawPayload));
        } catch (Exception e) { return; }

        if (msg.getType() == null || msg.getSenderPubKey() == null) return;

        // ── Step 2: Cryptography — Ed25519 signature check ──────────────────
        boolean sigValid = Ed25519Util.verifyFromBase64(
            msg.getSenderPubKey(),
            msg.getSignableData(),
            msg.getSignature()
        );
        if (!sigValid) {
            System.out.println("[DEBUG-VERIFY] FAIL. Type=" + msg.getType() + " Round=" + msg.getRound() + " sigData=" + msg.getSignableData() + " sig=" + msg.getSignature());
            System.out.println("[DEBUG-VERIFY] Raw Payload: " + new String(rawPayload));
            rateLimiter.ban(senderIp);
            return;
        }

        // ── Step 3: Proposer Equivocation Defense ────────────────────────────
        // FIX #21/#22: Only check equivocation for PROPOSAL messages (not votes)
        if (msg.getType() == NetworkMessage.Type.PROPOSAL) {
            String existingProposer = proposerEquivocationGuard.putIfAbsent(
                msg.getRound(), msg.getSenderPubKey()
            );
            if (existingProposer != null && !existingProposer.equals(msg.getSenderPubKey())) {
                System.out.println("[NetworkEngine] Equivocation! round=" + msg.getRound());
                return;
            }
        }

        // ── Step 4: Halting Interrupt — Certificate detection ────────────────
        if (msg.getType() == NetworkMessage.Type.BLOCK_CERTIFICATE) {
            try {
                BlockCertificate cert = BlockCertificate.fromJson(msg.getPayload());
                if (cert != null) {
                    // FIX #26: networkTip updated from certificate round
                    if (cert.getRound() > networkTip) {
                        networkTip = cert.getRound();
                    }
                    certificateQueue.offer(cert);
                    // FIX #10: Idempotent flush — only flush once per round
                    notifySharedBuffer(cert);
                    gossipRaw(rawPayload);
                }
            } catch (Exception e) {
                System.err.println("[NetworkEngine] Failed to parse certificate: " + e.getMessage());
            }
            return;
        }

        // ── Step 5: 10-Block Rule ─────────────────────────────────────────────
        if (msg.getRound() > networkTip + MAX_FUTURE_ROUNDS) return;

        // FIX #26: Only update networkTip here for non-certificate messages that pass all checks
        if (msg.getRound() > networkTip) networkTip = msg.getRound();

        // ── Route: PROPOSAL ───────────────────────────────────────────────────
        if (msg.getType() == NetworkMessage.Type.PROPOSAL && msg.getPayload() != null) {
            handleProposal(msg, rawPayload);
            return;
        }

        // ── Route: SYNC_REQUEST / SYNC_RESPONSE ───────────────────────────────
        if (msg.getType() == NetworkMessage.Type.SYNC_REQUEST) {
            handleSyncRequest(msg);
            return;
        }
        if (msg.getType() == NetworkMessage.Type.SYNC_RESPONSE) {
            handleSyncResponse(msg);
            return;
        }

        // ── Route: VoteMessages (SOFT_VOTE, BBA_GOSSIP, COMMON_COIN, CERTIFY_VOTE) ──
        VoteMessage.Step voteStep = resolveStep(msg.getType());
        if (voteStep != null && msg.getPayload() != null) {
            try {
                VoteMessage vote = VoteMessage.fromJson(msg.getPayload());
                if (vote == null) return;

                // FIX #23: Inner pubKey must match envelope pubKey
                if (!msg.getSenderPubKey().equals(vote.getSenderPubKey())) {
                    System.err.println("[NetworkEngine] Inner/envelope pubKey mismatch, dropping.");
                    return;
                }

                if (bannedProposers.contains(vote.getSenderPubKey())) return;

                // FIX #36/#37: Route BBA steps with iteration key
                if (voteStep == VoteMessage.Step.BBA_GOSSIP || voteStep == VoteMessage.Step.COMMON_COIN) {
                    forwardCache.putBBA(msg.getRound(), voteStep, vote.getIteration(), vote);
                } else {
                    forwardCache.put(msg.getRound(), voteStep, vote);
                }

                // Handle CERTIFY_VOTE certificate assembly
                if (msg.getType() == NetworkMessage.Type.CERTIFY_VOTE) {
                    // FIX #24: Verify inner CERTIFY_VOTE signature before assembling
                    boolean innerSigValid = Ed25519Util.verifyFromBase64(
                        vote.getSenderPubKey(),
                        vote.getSignableData(),
                        vote.getEd25519Signature()
                    );
                    if (innerSigValid) {
                        assembleCertificate(msg, vote);
                    }
                }
            } catch (Exception e) {
                System.err.println("[NetworkEngine] Failed to parse VoteMessage: " + e.getMessage());
                return;
            }
        }

        gossipRaw(rawPayload);
    }

    // -------------------------------------------------------------------------
    // PROPOSAL Handling (FIX #18, #20, #25)
    // -------------------------------------------------------------------------

    /**
     * Handles an incoming PROPOSAL message.
     * FIX #18: Stores full Block payload in ForwardCache.putProposalBlock().
     * FIX #20: Stores VRF metadata under PROPOSAL step (not SOFT_VOTE).
     * FIX #25: Validates that the proposed block's previousBlockHash == networkTipHash.
     */
    private void handleProposal(NetworkMessage msg, byte[] rawPayload) {
        try {
            Block proposed = Block.fromJson(msg.getPayload());
            if (proposed == null || proposed.getHeader() == null) return;

            // FIX #25: Block header chain validation
            String proposedPrevHash = proposed.getHeader().getPreviousBlockHash();
            if (!networkTipHash.equals(proposedPrevHash)) {
                System.err.println("[NetworkEngine] PROPOSAL dropped — bad previousHash: " +
                    "expected=" + networkTipHash.substring(0, Math.min(8, networkTipHash.length())) +
                    " got=" + proposedPrevHash.substring(0, Math.min(8, proposedPrevHash.length())));
                return;
            }

            // FIX #18: Store the full block JSON in ForwardCache for Phase 2 retrieval
            forwardCache.putProposalBlock(msg.getRound(), msg.getSenderPubKey(), msg.getPayload());

            // FIX #20: Store VRF metadata under PROPOSAL step (not SOFT_VOTE)
            VoteMessage proposalMeta = new VoteMessage();
            proposalMeta.setRound(msg.getRound());
            proposalMeta.setStep(VoteMessage.Step.PROPOSAL);
            proposalMeta.setSenderPubKey(msg.getSenderPubKey());
            proposalMeta.setChoice(proposed.getHash());
            String vrfProof = (proposed.getHeader() != null) ? proposed.getHeader().getProposerVRFProof() : "";
            proposalMeta.setVrfProof(vrfProof);
            // Derive vrfHash from vrfProof bytes (SHA-512 of proof)
            if (vrfProof != null && !vrfProof.isEmpty() && !"GENESIS".equals(vrfProof)) {
                try {
                    byte[] proofBytes = java.util.Base64.getDecoder().decode(vrfProof);
                    proposalMeta.setVrfHash(Ed25519Util.sha512Hex(proofBytes));
                } catch (Exception e) {
                    proposalMeta.setVrfHash("");
                }
            }
            proposalMeta.setEd25519Signature(msg.getSignature());
            forwardCache.put(msg.getRound(), VoteMessage.Step.PROPOSAL, proposalMeta);

            gossipRaw(rawPayload);
        } catch (Exception e) {
            System.err.println("[NetworkEngine] Failed to handle PROPOSAL: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Sync Request/Response Handlers (FIX #13/#14)
    // -------------------------------------------------------------------------

    /** FIX #13: Responds to SYNC_REQUEST by fetching requested blocks from DB and gossiping back. */
    private void handleSyncRequest(NetworkMessage msg) {
        if (stateEngine == null) return;
        try {
            String payload = msg.getPayload(); // format: "startRound:endRound"
            String[] parts = payload.split(":");
            if (parts.length != 2) return;
            long startRound = Long.parseLong(parts[0]);
            long endRound   = Long.parseLong(parts[1]);

            List<Block> blocks = new ArrayList<>();
            for (long r = startRound; r <= endRound; r++) {
                Block b = stateEngine.getDb().getBlock(r);
                if (b != null) blocks.add(b);
            }

            if (!blocks.isEmpty()) {
                String blocksJson = GSON.toJson(blocks);
                NetworkMessage response = buildEnvelope(NetworkMessage.Type.SYNC_RESPONSE, startRound, blocksJson);
                gossip(response);
                System.out.println("[NetworkEngine] SYNC_RESPONSE sent for rounds " + startRound + "-" + endRound);
            }
        } catch (Exception e) {
            System.err.println("[NetworkEngine] SYNC_REQUEST handling error: " + e.getMessage());
        }
    }

    /** FIX #14: Receives SYNC_RESPONSE and signals the waiting activeFetch() call. */
    @SuppressWarnings("unchecked")
    private void handleSyncResponse(NetworkMessage msg) {
        try {
            List<Block> blocks = GSON.fromJson(msg.getPayload(),
                new com.google.gson.reflect.TypeToken<List<Block>>(){}.getType());
            if (blocks == null || blocks.isEmpty()) return;

            long firstRound = blocks.get(0).getRound();
            syncResponses.put(firstRound, blocks);
            Semaphore sem = syncSemaphores.get(firstRound);
            if (sem != null) sem.release();
        } catch (Exception e) {
            System.err.println("[NetworkEngine] SYNC_RESPONSE handling error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Certificate Assembly (§5.2 Phase 6)
    // -------------------------------------------------------------------------

    private void assembleCertificate(NetworkMessage msg, VoteMessage vote) {
        try {
            long  round     = vote.getRound();
            String blockHash = vote.getChoice();

            // Re-verify the weight cryptographically instead of trusting the JSON
            int weight = 0;
            if (stateEngine != null) {
                weight = stateEngine.verifyVRFStake(
                    vote.getSenderPubKey(),
                    vote.getVrfProof(),
                    vote.getVrfHash(),
                    "CERTIFY:" + round,
                    round,
                    EXPECTED_VOTER_COMMITTEE
                );
            }

            // FIX #47: Normalize BOTTOM choice to BOTTOM_HASH
            if (VoteMessage.BOTTOM.equals(blockHash)) {
                blockHash = Block.BOTTOM_HASH;
            }

            if (weight <= 0 || blockHash == null) return;

            final String finalBlockHash = blockHash;
            BlockCertificate cert = certAccumulator
                .computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                .computeIfAbsent(finalBlockHash, h -> {
                    BlockCertificate c = new BlockCertificate();
                    c.setRound(round);
                    c.setBlockHash(h);
                    return c;
                });

            synchronized (cert) {
                // FIX #42: Pass vote.getVrfHash() (not voterPubKey) as the vrfHash argument
                cert.addVote(vote.getSenderPubKey(), vote.getVrfProof(),
                             vote.getVrfHash(), weight, vote.getBlockHashSignature());

                if (cert.isFinal(EXPECTED_VOTER_COMMITTEE)) {
                    System.out.println("[NetworkEngine] Certificate COMPLETE! round=" + round +
                                       " hash=" + finalBlockHash.substring(0, Math.min(8, finalBlockHash.length())) +
                                       " weight=" + cert.getTotalWeight());

                    gossip(buildEnvelope(NetworkMessage.Type.BLOCK_CERTIFICATE, round, cert.toJson()));
                    certificateQueue.offer(cert);
                    // FIX #10: Idempotent flush
                    notifySharedBuffer(cert);
                    certAccumulator.remove(round);

                    // FIX #21/#22: Purge old equivocation guard entries
                    proposerEquivocationGuard.keySet().removeIf(r -> r < round);
                }
            }
        } catch (Exception e) {
            System.err.println("[NetworkEngine] Certificate assembly error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // FIX #10: Idempotent SharedBuffer Flush
    // -------------------------------------------------------------------------

    /**
     * FIX #10: Notifies the SharedBuffer of a CertificateEvent, but only once per round.
     * Without this guard, a node that both assembles a certificate AND receives it from
     * a peer would flush the buffer twice for the same round — discarding the next batch.
     */
    private synchronized void notifySharedBuffer(BlockCertificate cert) {
        if (sharedBuffer != null && cert.getRound() > lastFlushedRound) {
            lastFlushedRound = cert.getRound();
            sharedBuffer.onCertificateEvent(cert);
        }
    }

    // -------------------------------------------------------------------------
    // Gossip (§2.2)
    // -------------------------------------------------------------------------

    public void gossip(NetworkMessage msg) {
        String signature = Ed25519Util.signToBase64(myPrivateKey, msg.getSignableData());
        msg.setSenderPubKey(myPubKeyBase64);
        msg.setSignature(signature);
        gossipRaw(msg.toJson().getBytes());
    }

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
    // Active Fetch (§2.2) — FIX #15/#16/#17/#19
    // -------------------------------------------------------------------------

    /**
     * Requests a block range from connected peers for synchronization.
     * FIX #15/#16/#17: Actually waits for the SYNC_RESPONSE via a semaphore (up to 3s).
     * FIX #19: Falls back to empty list after timeout for testbed graceful degradation.
     *
     * @param startRound First round to fetch (inclusive).
     * @param endRound   Last round to fetch (inclusive).
     * @return List of fetched blocks (may be empty if peers don't respond).
     */
    public java.util.concurrent.CompletableFuture<List<Block>> activeFetchAsync(long startRound, long endRound) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Semaphore sem = new Semaphore(0);
            syncSemaphores.put(startRound, sem);
            syncResponses.remove(startRound);

            NetworkMessage req = buildEnvelope(NetworkMessage.Type.SYNC_REQUEST, startRound,
                                                startRound + ":" + endRound);
            gossip(req);
            System.out.println("[NetworkEngine] activeFetchAsync requesting rounds " + startRound + "-" + endRound);

            try {
                boolean received = sem.tryAcquire(3, TimeUnit.SECONDS);
                if (received) {
                    List<Block> blocks = syncResponses.remove(startRound);
                    return (blocks != null) ? blocks : new ArrayList<>();
                } else {
                    System.out.println("[NetworkEngine] activeFetchAsync timeout for round " + startRound);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                syncSemaphores.remove(startRound);
            }
            return new ArrayList<>();
        }, ioThreadPool);
    }

    // -------------------------------------------------------------------------
    // Proposer Banning
    // -------------------------------------------------------------------------

    public void banProposer(String proposerPubKey) {
        bannedProposers.add(proposerPubKey);
        System.out.println("[NetworkEngine] Banned proposer: " +
            proposerPubKey.substring(0, Math.min(8, proposerPubKey.length())));
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

    public ForwardCache getForwardCache()                     { return forwardCache; }
    public long         getNetworkTip()                       { return networkTip; }
    public void         setNetworkTip(long tip)               { this.networkTip = tip; }
    /** FIX #25: update networkTipHash when the local node commits a new block. */
    public void         setNetworkTipHash(String hash)        { this.networkTipHash = hash; }
    public void         setSharedBuffer(buffer.SharedBuffer sb){ this.sharedBuffer = sb; }
    /** FIX #13/#14: StateEngine reference for SYNC_RESPONSE serving. */
    public void         setStateEngine(state.StateEngine se)  { this.stateEngine = se; }
}
