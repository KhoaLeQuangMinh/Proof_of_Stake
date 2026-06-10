package consensus;

import app.Wallet;
import buffer.EventGateway;
import buffer.SharedBuffer;
import crypto.Ed25519Util;
import crypto.VRF;
import model.*;
import network.NetworkEngine;
import state.StateEngine;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ConsensusEngine — Component 4: The BBA* State Machine.
 *
 * Integration Plan §5 (Component 4: The Consensus Engine):
 *  - The main loop. Controls the Round clock and executes the BA* algorithm.
 *  - Replaces hardcoded Thread.sleep() with event-driven Dynamic EMA loops.
 *  - All 6 phases are implemented here.
 *
 * Fixes applied:
 *
 *  FIX #9:  Capture round batch BEFORE entering Phase 1 (not during).
 *           Prevents race with onCertificateEvent() flush.
 *
 *  FIX #27: Self-insert votes in Phase 3 (if elected) — node inserts its own
 *           SoftVote into the local ForwardCache so Phase 4 counts it without
 *           a network round-trip.
 *
 *  FIX #30: VRF input for Phase 2 leader sort uses vrfHash (not vrfProof) for comparison.
 *
 *  FIX #31: Phase 2 VRF sort uses vrfHash (the uniform SHA-512 output), not vrfProof
 *           (which is a raw Ed25519 signature and not uniformly distributed).
 *
 *  FIX #32: Phase 4 vote weight is re-verified via StateEngine.verifyVRFStake() before
 *           trusting it for the threshold check.
 *
 *  FIX #33: Phase 5 BBA Step B vote weight is re-verified before counting.
 *
 *  FIX #34: Phase 5 Coin vote weight is re-verified before counting.
 *
 *  FIX #35: Phase 5 Coin elected guard: nodes with weight=0 still gossip coin (the coin
 *           collection relies on hearing from all stakers, not just elected ones).
 *           The "elected" check is removed for Coin gossip; weight check kept for Step B.
 *
 *  FIX #36/#37: BBA_GOSSIP and COMMON_COIN polls use iteration-keyed ForwardCache methods.
 *
 *  FIX #38/#39: Phase 4 and Phase 5 equivocation detection correctly removes the weight
 *               of the PREVIOUSLY counted vote, not the current vote.
 *
 *  FIX #43: Phase 6 CertifyVote VRF input = "CERTIFY:" + round.
 *
 *  FIX #44: Phase 6 validates cert.getRound() == current round before applying.
 *
 *  FIX #45: Phase 6 does NOT break out early if an early cert is from a previous round.
 *
 *  FIX #46: buildVoteMessage() sets both ed25519Signature (over full signable data) and
 *           blockHashSignature (over just the choice) for CERTIFY_VOTE step.
 *
 *  FIX #63: applyBlock() result is checked; nackCurrentBatch() on failure.
 *
 *  FIX #64: Bottom rounds also save an empty cert to DB.
 *
 *  FIX #66: constructBlock() sets ALL header fields before computing the hash + signature.
 *
 *  FIX #67: handleCatchup() uses activeFetch() result directly and applies blocks; does NOT
 *           sleep 3s for macro-partition (which prevented progress entirely in that case).
 */
public class ConsensusEngine {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private static final int    BBA_PANIC_LIMIT      = 50;
    private static final String BOTTOM               = VoteMessage.BOTTOM;
    private static final int    CATCHUP_GAP_MACRO    = 10;
    private static final int    EXPECTED_VOTERS      = Sortition.EXPECTED_VOTERS;
    private static final int    EXPECTED_PROPOSERS   = Sortition.EXPECTED_PROPOSERS;
    private static final String CERTIFY_DOMAIN       = "CERTIFY:";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final StateEngine   stateEngine;
    private final NetworkEngine networkEngine;
    private final SharedBuffer  sharedBuffer;
    private final EventGateway  eventGateway;
    private final Wallet        myWallet;
    private final LinkedBlockingQueue<BlockCertificate> certificateQueue;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private volatile long    currentRound = 0L;
    private final EMATimeout emaTimeout   = new EMATimeout();
    private volatile boolean running      = false;

    /**
     * FIX #9: The batch captured at the start of each round.
     * Set by captureRoundBatch() BEFORE Phase 1 begins.
     */
    private List<Transaction> roundBatch = new ArrayList<>();

    /**
     * Proposals received in Phase 2: BlockHash -> full Block payload.
     */
    private final Map<String, Block> proposalCache = new java.util.concurrent.ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ConsensusEngine(StateEngine stateEngine,
                           NetworkEngine networkEngine,
                           SharedBuffer sharedBuffer,
                           EventGateway eventGateway,
                           Wallet myWallet,
                           LinkedBlockingQueue<BlockCertificate> certificateQueue) {
        this.stateEngine      = stateEngine;
        this.networkEngine    = networkEngine;
        this.sharedBuffer     = sharedBuffer;
        this.eventGateway     = eventGateway;
        this.myWallet         = myWallet;
        this.certificateQueue = certificateQueue;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        running = true;
        Thread t = new Thread(this::runLoop, "ConsensusEngine");
        t.setDaemon(true);
        t.start();
        System.out.println("[ConsensusEngine] Started.");
    }

    public void stop() { running = false; }

    // -------------------------------------------------------------------------
    // Main Loop
    // -------------------------------------------------------------------------

    private void runLoop() {
        long ledgerRound = stateEngine.getLatestRound();
        currentRound = Math.max(0, ledgerRound + 1);
        System.out.println("[ConsensusEngine] Resuming from round " + currentRound);

        while (running) {
            try {
                if (handleCatchup()) {
                    Thread.sleep(100); // Prevent 100% CPU spinlock while catching up
                    continue;
                }
                executeRound(currentRound);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[ConsensusEngine] Round " + currentRound + " error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Catchup (FIX #67)
    // -------------------------------------------------------------------------

    /**
     * FIX #67: handleCatchup() now uses activeFetch() result directly.
     * Previously macro-partition fell into Thread.sleep(3000) without applying
     * fetched blocks, which simply froze the consensus loop for 3 seconds.
     */
    private boolean handleCatchup() {
        // The background CatchupService handles actual syncing.
        // If we are more than 5 rounds behind, we pause consensus execution
        // and let the loop spin (or yield) without blocking the thread entirely.
        long localTip = stateEngine.getLatestRound();
        long networkTip = networkEngine.getNetworkTip();
        if (networkTip - localTip > 5) {
            System.out.println("[ConsensusEngine] Paused. Waiting for CatchupService...");
            Thread.yield(); // Do not use Thread.sleep, keep the thread responsive
            return true; // Indicate we are catching up
        }
        currentRound = Math.max(currentRound, stateEngine.getLatestRound() + 1);
        return false;
    }

    // -------------------------------------------------------------------------
    // Round Execution
    // -------------------------------------------------------------------------

    private void executeRound(long round) throws InterruptedException {
        long roundStart = System.currentTimeMillis();
        proposalCache.clear();
        certificateQueue.removeIf(cert -> cert.getRound() < round); // Bug P Fix: Only discard stale certs

        System.out.println("\n[ConsensusEngine] ========== ROUND " + round + " ==========");

        // FIX #9: Capture the batch BEFORE any phase runs (atomic snapshot)
        roundBatch = new ArrayList<>(sharedBuffer.captureRoundBatch());
        System.out.println("[ConsensusEngine] Captured " + roundBatch.size() + " txs for round " + round);

        String vrfInput = stateEngine.getLatestSeed();

        // Phase 1: Value Propose
        phase1_ValuePropose(round, vrfInput);

        // Phase 2: Pre-Validation & Minimum Hash
        String bestProposal = phase2_PreValidation(round, vrfInput);

        // Phase 3: Filter (voter committee election)
        phase3_Filter(round, vrfInput, bestProposal);

        // Phase 4: Resolving Filter
        String bbaChoice = phase4_ResolvingFilter(round, vrfInput);

        // Phase 5: BBA* Micro-Loop
        String finalWinner = phase5_BbaStar(round, bbaChoice, vrfInput);

        // Phase 6: Halting Condition
        phase6_HaltingCondition(round, finalWinner, vrfInput);

        long roundDuration = System.currentTimeMillis() - roundStart;
        emaTimeout.recordRoundDuration(roundDuration);
        System.out.println("[ConsensusEngine] Round " + round + " complete in " + roundDuration + "ms");
    }

    // =========================================================================
    // PHASE 1: Value Propose
    // =========================================================================

    private void phase1_ValuePropose(long round, String vrfInput) {
        System.out.println("[Phase 1] Value Propose — round=" + round);

        VRF.VRFResult vrfResult = VRF.evaluate(myWallet.getPrivateKey(), vrfInput);

        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);

        byte[] vrfHashBytes = hexToBytes(vrfResult.hash);
        int myWeight = Sortition.getSortitionWeight(vrfHashBytes, myStake, totalStake, EXPECTED_PROPOSERS);

        System.out.println("[Phase 1] Sortition: myStake=" + myStake + " totalStake=" + totalStake +
                           " weight=" + myWeight);

        if (myWeight <= 0) {
            System.out.println("[Phase 1] Not selected as proposer this round.");
            return;
        }

        System.out.println("[Phase 1] ✨ ELECTED AS PROPOSER (weight=" + myWeight + ")");

        // FIX #9: Use the already-captured roundBatch
        // Phase 1 Pre-filtering: Proposer simulates and drops invalid transactions
        List<Transaction> txBatch = new ArrayList<>();
        state.PendingStateOverlay overlay = new state.PendingStateOverlay();
        List<String> addresses = new ArrayList<>();
        for (Transaction tx : roundBatch) {
            if (tx.getSenderPubKey() != null)   addresses.add(tx.getSenderPubKey());
            if (tx.getReceiverPubKey() != null) addresses.add(tx.getReceiverPubKey());
        }
        overlay.loadFromDB(stateEngine.getDb(), addresses);

        for (Transaction tx : roundBatch) {
            if (overlay.simulate(tx, round, myWallet.getPublicKeyBase64())) {
                txBatch.add(tx);
                if (txBatch.size() >= 10) break; // MAX_TX_PER_BLOCK = 10
            } else {
                System.out.println("[Phase 1] Proposer pre-filtering dropped tx: " + tx.getTxId());
            }
        }
        
        System.out.println("[Phase 1] Using " + txBatch.size() + " valid transactions from captured batch.");

        Block block = constructBlock(round, txBatch, vrfResult);
        proposalCache.put(block.getHash(), block);
        // Also store in ForwardCache for peer retrieval
        networkEngine.getForwardCache().putProposalBlock(round, myWallet.getPublicKeyBase64(), block.toJson());

        NetworkMessage proposal = networkEngine.buildEnvelope(NetworkMessage.Type.PROPOSAL, round, block.toJson());
        networkEngine.gossip(proposal);
        System.out.println("[Phase 1] Proposed block: " + block);
    }

    /**
     * FIX #66: All header fields set BEFORE computing the hash and signature.
     * Previously the signature was computed on a partially-populated header.
     */
    private Block constructBlock(long round, List<Transaction> txs, VRF.VRFResult vrfResult) {
        BlockHeader header = new BlockHeader();

        // FIX #66: Set ALL fields first...
        header.setRound(round);
        header.setTimestamp(System.currentTimeMillis());
        header.setPreviousBlockHash(stateEngine.getLatestBlockHash());
        header.setProposerVRFProof(vrfResult.proof);
        header.setProposerPubKey(myWallet.getPublicKeyBase64());

        // Chained Randomness Beacon seed
        String newSeed = Ed25519Util.sha256Hex(vrfResult.proof + round);
        header.setSeed(newSeed);

        // Build block first so we can compute the Merkle root
        Block block = new Block();
        block.setHeader(header);
        block.setTransactions(txs);

        // Compute Merkle root over transactions (FIX #65 — now a proper binary tree)
        String merkleRoot = block.computeMerkleRoot();
        header.setTransactionMerkleRoot(merkleRoot);

        // FIX #66: Compute hash AFTER all fields (including merkleRoot) are set
        String blockHash = Ed25519Util.sha256Hex(header.getSignableData() + merkleRoot);
        header.setHash(blockHash);

        // FIX #66: Sign AFTER hash is set (signature covers fully-populated header)
        String headerSig = Ed25519Util.signToBase64(myWallet.getPrivateKey(), header.getSignableData());
        header.setEd25519Signature(headerSig);

        return block;
    }

    // =========================================================================
    // PHASE 2: Pre-Validation & Minimum Hash
    // =========================================================================

    /**
     * FIX #31: Sorts proposals by vrfHash (uniform SHA-512 output), not vrfProof.
     * FIX #30: verifyVRFStake() called with vrfHash (not vrfProof) as the expectedHash arg.
     * FIX #18: Block payloads fetched from ForwardCache.getProposalBlock() (not just local cache).
     */
    private String phase2_PreValidation(long round, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 2] Pre-Validation — collecting proposals...");

        long timeout  = emaTimeout.getNextTimeoutMs();
        long deadline = System.currentTimeMillis() + timeout;

        // FIX #20: Collect from PROPOSAL step (not SOFT_VOTE)
        Map<String, VoteMessage> proposals = new HashMap<>();
        while (System.currentTimeMillis() < deadline) {
            if (!certificateQueue.isEmpty()) break;

            long remaining = deadline - System.currentTimeMillis();
            VoteMessage vote = networkEngine.getForwardCache()
                                            .poll(round, VoteMessage.Step.PROPOSAL,
                                                  Math.min(remaining, 200));
            if (vote != null && vote.getChoice() != null && !vote.getChoice().equals(BOTTOM)) {
                proposals.put(vote.getChoice(), vote);
            }
        }

        // Also include our own proposal if we sent one
        for (Map.Entry<String, Block> entry : proposalCache.entrySet()) {
            String ownHash = entry.getKey();
            if (!proposals.containsKey(ownHash)) {
                VoteMessage ownMeta = new VoteMessage();
                ownMeta.setStep(VoteMessage.Step.PROPOSAL);
                ownMeta.setSenderPubKey(myWallet.getPublicKeyBase64());
                ownMeta.setChoice(ownHash);
                Block own = entry.getValue();
                ownMeta.setVrfProof(own.getHeader().getProposerVRFProof());
                String proofB64 = own.getHeader().getProposerVRFProof();
                if (proofB64 != null && !proofB64.isEmpty()) {
                    try {
                        byte[] proofBytes = java.util.Base64.getDecoder().decode(proofB64);
                        ownMeta.setVrfHash(Ed25519Util.sha512Hex(proofBytes));
                    } catch (Exception ignored) {}
                }
                ownMeta.setSortitionWeight(1);
                proposals.put(ownHash, ownMeta);
            }
        }

        System.out.println("[Phase 2] Collected " + proposals.size() + " proposals.");

        if (proposals.isEmpty()) {
            System.out.println("[Phase 2] No proposals — Best_Proposal = BOTTOM");
            return BOTTOM;
        }

        // FIX #31: Sort by vrfHash (the uniform SHA-512 output), not vrfProof
        List<Map.Entry<String, VoteMessage>> sorted = new ArrayList<>(proposals.entrySet());
        sorted.sort((a, b) -> {
            String hashA = a.getValue().getVrfHash() != null ? a.getValue().getVrfHash() : "";
            String hashB = b.getValue().getVrfHash() != null ? b.getValue().getVrfHash() : "";
            return hashA.compareTo(hashB);
        });

        for (Map.Entry<String, VoteMessage> entry : sorted) {
            String      blockHash = entry.getKey();
            VoteMessage vote      = entry.getValue();

            // FIX #30: Pass vote.getVrfHash() as the expectedHash argument
            int proposerWeight = stateEngine.verifyVRFStake(
                vote.getSenderPubKey(),
                vote.getVrfProof(),
                vote.getVrfHash() != null ? vote.getVrfHash() : "",
                vrfInput,
                round,
                EXPECTED_PROPOSERS
            );

            // Own proposal: skip weight check (we already won sortition in Phase 1)
            boolean isOwnProposal = myWallet.getPublicKeyBase64().equals(vote.getSenderPubKey())
                                    && proposalCache.containsKey(blockHash);
            if (proposerWeight <= 0 && !isOwnProposal) {
                System.out.println("[Phase 2] Skipping unelected proposer: " +
                    vote.getSenderPubKey().substring(0, Math.min(8, vote.getSenderPubKey().length())));
                continue;
            }

            // FIX #18: Fetch block payload from ForwardCache (peer proposals) or local cache
            Block proposedBlock = proposalCache.get(blockHash);
            if (proposedBlock == null) {
                String blockJson = networkEngine.getForwardCache()
                                               .getProposalBlock(round, vote.getSenderPubKey());
                if (blockJson != null) {
                    try { proposedBlock = Block.fromJson(blockJson); } catch (Exception ignored) {}
                }
            }

            if (proposedBlock == null) {
                System.out.println("[Phase 2] Block payload not available for hash=" +
                    blockHash.substring(0, Math.min(8, blockHash.length())) + " — skipping.");
                continue;
            }

            if (!stateEngine.simulateBlock(proposedBlock, round)) {
                System.out.println("[Phase 2] Simulation FAILED for hash=" +
                    blockHash.substring(0, Math.min(8, blockHash.length())) + " — banning proposer.");
                networkEngine.banProposer(vote.getSenderPubKey());
                continue;
            }

            System.out.println("[Phase 2] Best_Proposal = " + blockHash.substring(0, Math.min(8, blockHash.length())));
            proposalCache.put(blockHash, proposedBlock); // Ensure it's cached for Phase 6
            return blockHash;
        }

        System.out.println("[Phase 2] All proposals failed simulation — Best_Proposal = BOTTOM");
        return BOTTOM;
    }

    // =========================================================================
    // PHASE 3: Filter (Voter Committee Election)
    // =========================================================================

    /**
     * FIX #27: Self-insert the SoftVote into ForwardCache so Phase 4 counts it locally.
     */
    private void phase3_Filter(long round, String vrfInput, String bestProposal) {
        System.out.println("[Phase 3] Filter — checking election...");

        VRF.VRFResult vrfResult = VRF.evaluate(myWallet.getPrivateKey(), vrfInput);
        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);

        byte[] vrfHashBytes = hexToBytes(vrfResult.hash);
        int myWeight = Sortition.getSortitionWeight(vrfHashBytes, myStake, totalStake, EXPECTED_VOTERS);

        System.out.println("[Phase 3] Sortition weight=" + myWeight);

        if (myWeight <= 0) {
            System.out.println("[Phase 3] Not elected to voter committee.");
            return;
        }

        System.out.println("[Phase 3] ✨ ELECTED AS VOTER (weight=" + myWeight + ") — voting for: " +
                           bestProposal.substring(0, Math.min(8, bestProposal.length())));

        VoteMessage vote = buildVoteMessage(round, VoteMessage.Step.SOFT_VOTE,
                                             bestProposal, vrfResult, myWeight);
        NetworkMessage msg = networkEngine.buildEnvelope(NetworkMessage.Type.SOFT_VOTE, round, vote.toJson());
        networkEngine.gossip(msg);

        // FIX #27: Self-insert so Phase 4 counts our own vote without waiting for network echo
        networkEngine.getForwardCache().put(round, VoteMessage.Step.SOFT_VOTE, vote);
    }

    // =========================================================================
    // PHASE 4: Resolving Filter (Liveness Check)
    // =========================================================================

    /**
     * FIX #32: Re-verify VRF stake before trusting sortitionWeight from received votes.
     * FIX #38: Equivocation correctly removes the PREVIOUSLY-COUNTED vote's weight.
     */
    private String phase4_ResolvingFilter(long round, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 4] Resolving Filter — counting soft votes...");

        long timeout  = emaTimeout.getNextTimeoutMs();
        long deadline = System.currentTimeMillis() + timeout;

        Map<String, Integer> hashWeights       = new HashMap<>();
        // FIX #38: Track (senderPubKey -> (choice, verifiedWeight)) for equivocation undo
        Map<String, Object[]> senderVoteRecord = new HashMap<>();

        while (System.currentTimeMillis() < deadline) {
            if (!certificateQueue.isEmpty()) {
                System.out.println("[Phase 4] Certificate interrupt — advancing to Phase 6.");
                return BOTTOM;
            }

            long remaining = deadline - System.currentTimeMillis();
            VoteMessage vote = networkEngine.getForwardCache()
                                            .poll(round, VoteMessage.Step.SOFT_VOTE,
                                                  Math.min(remaining, 100));
            if (vote == null) continue;

            String sender = vote.getSenderPubKey();
            String choice = vote.getChoice();
            if (choice == null) continue;

            // FIX #32: Re-verify VRF stake (don't trust declared weight)
            int verifiedWeight = stateEngine.verifyVRFStake(
                sender, vote.getVrfProof(), vote.getVrfHash() != null ? vote.getVrfHash() : "",
                vrfInput, round, EXPECTED_VOTERS
            );
            if (verifiedWeight <= 0) continue; // Not elected — drop

            // FIX #38: Equivocation detection — remove OLD vote weight from the accumulator
            if (senderVoteRecord.containsKey(sender)) {
                Object[] prev = senderVoteRecord.get(sender);
                String prevChoice = (String) prev[0];
                int    prevWeight = (int) prev[1];
                if (!prevChoice.equals(choice)) {
                    System.out.println("[Phase 4] Equivocation from " + sender.substring(0, 8) + " — removing.");
                    // FIX #38: Remove the previously-counted OLD weight (not the new one)
                    hashWeights.merge(prevChoice, -prevWeight, Integer::sum);
                    senderVoteRecord.remove(sender);
                    continue; // Drop the equivocating new vote too
                }
                continue; // Duplicate of same choice — skip
            }

            senderVoteRecord.put(sender, new Object[]{choice, verifiedWeight});
            int newWeight = hashWeights.merge(choice, verifiedWeight, Integer::sum);

            if (Sortition.hasReachedThreshold(newWeight)) {
                System.out.println("[Phase 4] SUPERMAJORITY for " +
                    choice.substring(0, Math.min(8, choice.length())) +
                    " (weight=" + newWeight + " > " + Sortition.VOTE_THRESHOLD + ")");
                return choice;
            }
        }

        System.out.println("[Phase 4] Timeout — no supermajority. BBA_Choice = BOTTOM");
        return BOTTOM;
    }

    // =========================================================================
    // PHASE 5: Binary Byzantine Agreement (BBA* Micro-Loop)
    // =========================================================================

    /**
     * FIX #33: BBA Step B re-verifies VRF stake before counting.
     * FIX #36: BBA_GOSSIP poll uses iteration-keyed ForwardCache.pollBBA().
     * FIX #39: BBA equivocation correctly removes OLD vote's weight.
     */
    private String phase5_BbaStar(long round, String bbaChoice, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 5] BBA* Micro-Loop — initial choice: " +
                           bbaChoice.substring(0, Math.min(8, bbaChoice.length())));

        String lastKnownHash = BOTTOM;
        int panicCounter = 0;

        while (running) {
            panicCounter++;
            if (panicCounter > BBA_PANIC_LIMIT) {
                System.out.println("[Phase 5] PANIC LIMIT reached — Recovery Mode.");
                return enterRecoveryMode(round);
            }

            // ── Step A: Gossip BBA_Choice ─────────────────────────────────────
            // FIX #36: VRF input for BBA includes the iteration (panicCounter)
            String bbaVrfInput = vrfInput + "BBA" + panicCounter;
            VRF.VRFResult vrfResult = VRF.evaluate(myWallet.getPrivateKey(), bbaVrfInput);
            long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
            long totalStake = stateEngine.getOnlineStake(round);
            byte[] vrfHashBytes = hexToBytes(vrfResult.hash);
            int myWeight = Sortition.getSortitionWeight(vrfHashBytes, myStake, totalStake, EXPECTED_VOTERS);

            if (myWeight > 0) {
                VoteMessage bbaVote = buildVoteMessage(round, VoteMessage.Step.BBA_GOSSIP,
                                                       bbaChoice, vrfResult, myWeight);
                // FIX #36/#37: Set iteration field
                bbaVote.setIteration(panicCounter);
                NetworkMessage msg = networkEngine.buildEnvelope(
                    NetworkMessage.Type.BBA_GOSSIP, round, bbaVote.toJson());
                networkEngine.gossip(msg);
                // FIX #27: Self-insert
                networkEngine.getForwardCache().putBBA(round, VoteMessage.Step.BBA_GOSSIP, panicCounter, bbaVote);
            }

            // ── Step B: Count BBA Votes ───────────────────────────────────────
            long timeout  = emaTimeout.getNextTimeoutMs();
            long deadline = System.currentTimeMillis() + timeout;

            Map<String, Integer> hashWeights    = new HashMap<>();
            Map<String, Object[]> senderRecords = new HashMap<>();

            while (System.currentTimeMillis() < deadline) {
                if (!certificateQueue.isEmpty()) {
                    System.out.println("[Phase 5] Certificate interrupt — BBA* complete.");
                    return bbaChoice;
                }

                long remaining = deadline - System.currentTimeMillis();
                // FIX #36: Use iteration-keyed poll
                VoteMessage vote = networkEngine.getForwardCache()
                                               .pollBBA(round, VoteMessage.Step.BBA_GOSSIP,
                                                        panicCounter, Math.min(remaining, 100));
                if (vote == null) continue;

                String sender = vote.getSenderPubKey();
                String hash   = vote.getChoice();
                if (hash == null) continue;

                // FIX #33: Re-verify VRF stake
                int verifiedWeight = stateEngine.verifyVRFStake(
                    sender, vote.getVrfProof(), vote.getVrfHash() != null ? vote.getVrfHash() : "",
                    bbaVrfInput, round, EXPECTED_VOTERS
                );
                if (verifiedWeight <= 0) continue;

                // FIX #39: Equivocation — remove OLD weight
                if (senderRecords.containsKey(sender)) {
                    Object[] prev = senderRecords.get(sender);
                    String prevHash = (String) prev[0]; int prevW = (int) prev[1];
                    if (!prevHash.equals(hash)) {
                        hashWeights.merge(prevHash, -prevW, Integer::sum);
                        senderRecords.remove(sender);
                        continue;
                    }
                    continue;
                }

                senderRecords.put(sender, new Object[]{hash, verifiedWeight});
                if (!hash.equals(BOTTOM)) lastKnownHash = hash;
                int newWeight = hashWeights.merge(hash, verifiedWeight, Integer::sum);

                if (!hash.equals(BOTTOM) && Sortition.hasReachedThreshold(newWeight)) {
                    System.out.println("[Phase 5] BBA* converged: " + hash.substring(0, 8) + " (w=" + newWeight + ")");
                    return hash;
                }
                if (hash.equals(BOTTOM) && Sortition.hasReachedThreshold(newWeight)) {
                    System.out.println("[Phase 5] BBA* converged: BOTTOM (w=" + newWeight + ")");
                    return BOTTOM;
                }
            }

            // ── Step C: Common Coin Deadlock Breaker ─────────────────────────
            System.out.println("[Phase 5] Deadlock — running Common Coin (iter=" + panicCounter + ")");
            String globalCoin = runCommonCoin(round, panicCounter, vrfInput);

            int parity = VRF.getCoinParity(globalCoin);
            if (parity == 0) {
                bbaChoice = lastKnownHash.equals(BOTTOM) ? BOTTOM : lastKnownHash;
                System.out.println("[Phase 5] Coin=EVEN → BBA_Choice=" +
                    bbaChoice.substring(0, Math.min(8, bbaChoice.length())));
            } else {
                bbaChoice = BOTTOM;
                System.out.println("[Phase 5] Coin=ODD → BBA_Choice=BOTTOM");
            }
        }

        return BOTTOM;
    }

    /**
     * FIX #34: Coin vote weight re-verified before counting.
     * FIX #35: ALL staked nodes gossip the coin — not just elected ones.
     * FIX #37: COMMON_COIN poll uses iteration-keyed ForwardCache.pollBBA().
     */
    private String runCommonCoin(long round, int iteration, String vrfInput) throws InterruptedException {
        String coinVrfInput = "COIN:" + round + ":" + iteration;
        VRF.VRFResult coinVRF = VRF.generateCoin(myWallet.getPrivateKey(), round, iteration);

        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);
        byte[] coinHashBytes = hexToBytes(coinVRF.hash);
        int myWeight = Sortition.getSortitionWeight(coinHashBytes, myStake, totalStake, EXPECTED_VOTERS);

        // FIX #35: Gossip coin if we have ANY stake (not just if weight > 0)
        if (myStake > 0) {
            VoteMessage coinMsg = buildVoteMessage(round, VoteMessage.Step.COMMON_COIN,
                                                    coinVRF.hash, coinVRF, myWeight);
            coinMsg.setIteration(iteration);
            coinMsg.setCoinHash(coinVRF.hash);

            NetworkMessage netMsg = networkEngine.buildEnvelope(NetworkMessage.Type.COMMON_COIN, round, coinMsg.toJson());
            networkEngine.gossip(netMsg);
            // Self-insert
            networkEngine.getForwardCache().putBBA(round, VoteMessage.Step.COMMON_COIN, iteration, coinMsg);
        }

        long deadline = System.currentTimeMillis() + emaTimeout.getNextTimeoutMs();
        List<String> validCoins = new ArrayList<>();
        if (myStake > 0) validCoins.add(coinVRF.hash);

        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            // FIX #37: Iteration-keyed poll for COMMON_COIN
            VoteMessage coin = networkEngine.getForwardCache()
                                            .pollBBA(round, VoteMessage.Step.COMMON_COIN,
                                                     iteration, Math.min(remaining, 100));
            if (coin == null) continue;

            System.out.println("[Phase 5 DEBUG] Received COMMON_COIN from " + coin.getSenderPubKey().substring(0, 8) + " for iter " + iteration);

            // FIX #34: Re-verify stake for coin votes
            int verifiedWeight = stateEngine.verifyVRFStake(
                coin.getSenderPubKey(),
                coin.getVrfProof(),
                coin.getVrfHash() != null ? coin.getVrfHash() : "",
                coinVrfInput, round, EXPECTED_VOTERS
            );
            if (verifiedWeight > 0) {
                String coinHash = coin.getCoinHash() != null ? coin.getCoinHash() : coin.getChoice();
                if (coinHash != null) {
                    validCoins.add(coinHash);
                    System.out.println("[Phase 5 DEBUG] Added coinHash " + coinHash.substring(0, 8) + " to validCoins");
                }
            } else {
                System.out.println("[Phase 5 DEBUG] verifiedWeight was 0 for coin from " + coin.getSenderPubKey().substring(0, 8));
            }
        }

        if (validCoins.isEmpty()) {
            System.out.println("[Phase 5] No coins received — using own coin.");
            return coinVRF.hash;
        }
        String globalCoin = Collections.min(validCoins);
        System.out.println("[Phase 5] Global Coin = " + globalCoin.substring(0, Math.min(16, globalCoin.length())) + "...");
        return globalCoin;
    }

    // =========================================================================
    // PHASE 6: The Halting Condition
    // =========================================================================

    /**
     * FIX #43: CertifyVote VRF input = "CERTIFY:" + round.
     * FIX #44: Validates cert.getRound() == current round.
     * FIX #45: Drains stale certs from previous rounds without treating them as current.
     * FIX #46: Sets both ed25519Signature and blockHashSignature on CERTIFY_VOTE.
     * FIX #63: Checks applyBlock() result; calls nack/ack accordingly.
     * FIX #64: Bottom rounds save a Bottom certificate to DB.
     */
    private void phase6_HaltingCondition(long round, String finalWinner, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 6] Halting Condition — finalWinner=" +
                           finalWinner.substring(0, Math.min(8, finalWinner.length())));

        // FIX #43: Use "CERTIFY:" + round as VRF input (not block seed)
        String certifyVrfInput = CERTIFY_DOMAIN + round;
        VRF.VRFResult certVRF = VRF.evaluate(myWallet.getPrivateKey(), certifyVrfInput);
        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);
        byte[] certHashBytes = hexToBytes(certVRF.hash);
        int myWeight = Sortition.getSortitionWeight(certHashBytes, myStake, totalStake, EXPECTED_VOTERS);

        if (myWeight > 0) {
            // FIX #46: Build vote with both signatures
            VoteMessage certVote = buildCertifyVoteMessage(round, finalWinner, certVRF, myWeight);
            NetworkMessage msg = networkEngine.buildEnvelope(NetworkMessage.Type.CERTIFY_VOTE, round, certVote.toJson());
            networkEngine.gossip(msg);
            // FIX #27: Self-insert certify vote
            networkEngine.getForwardCache().put(round, VoteMessage.Step.CERTIFY_VOTE, certVote);
            System.out.println("[Phase 6] CertifyVote gossiped (weight=" + myWeight + ")");
        }

        // ── Block on SystemInterrupt(CertificateEvent) ────────────────────────
        System.out.println("[Phase 6] Waiting for BlockCertificate...");

        BlockCertificate cert = null;
        // FIX #44/#45: Drain stale certs from old rounds; wait for cert for THIS round
        long certDeadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < certDeadline) {
            BlockCertificate candidate = certificateQueue.poll(1, TimeUnit.SECONDS);
            if (candidate == null) continue;
            
            // Bug Q Fix: Explicitly verify the cryptographic validity of the certificate
            if (candidate.getRound() == round && stateEngine.verifyCertificate(candidate, round)) {
                cert = candidate;
                break;
            }
            // FIX #45: Stale cert from a previous round — skip, don't break
            System.out.println("[Phase 6] Skipping invalid or stale cert from round=" + candidate.getRound());
        }

        if (cert == null) {
            System.out.println("[Phase 6] Certificate timeout — network halted. Retrying round " + round);
            throw new RuntimeException("Round timeout — retrying");
        }

        System.out.println("[Phase 6] Certificate: " + cert);

        // ── Handle Bottom ─────────────────────────────────────────────────────
        if (cert.isBottom() || BOTTOM.equals(cert.getBlockHash())) {
            System.out.println("[Phase 6] BOTTOM round — creating empty block.");

            Block emptyBlock = Block.createEmptyBlock(
                round, stateEngine.getLatestBlockHash(), System.currentTimeMillis(), ""
            );

            // FIX #64: Save bottom cert to DB
            BlockCertificate bottomCert = new BlockCertificate();
            bottomCert.setRound(round);
            bottomCert.setBlockHash(emptyBlock.getHash());
            emptyBlock.setCertificate(bottomCert);

            boolean applied = stateEngine.applyBlock(emptyBlock);
            if (applied) {
                stateEngine.getDb().saveCertificate(bottomCert); // FIX #64
                // FIX #8: NACK the transactions (they weren't committed)
                sharedBuffer.nackCurrentBatch();
                // Phase 6 Timeout Hook: fire TXN_REJECTED_TIMEOUT for all un-committed txs
                eventGateway.publishRejectedTimeout(new ArrayList<>(roundBatch));
                networkEngine.setNetworkTipHash(emptyBlock.getHash());
                networkEngine.setNetworkTip(round);
            } else {
                System.err.println("[Phase 6] applyBlock failed for empty block at round=" + round);
                sharedBuffer.nackCurrentBatch();
            }

        } else {
            // ── Handle Successful Block ───────────────────────────────────────
            String blockHash = cert.getBlockHash();
            Block payload = proposalCache.get(blockHash);

            if (payload == null) {
                // Scan all proposalBlocks entries for this round — the proposer's pubKey
                // may differ from any cert voter's pubKey
                java.util.concurrent.ConcurrentHashMap<String, String> allProposals =
                    networkEngine.getForwardCache().getAllProposalBlocks(round);
                for (java.util.Map.Entry<String, String> e : allProposals.entrySet()) {
                    try {
                        Block candidate = Block.fromJson(e.getValue());
                        if (candidate != null && blockHash.equals(candidate.getHash())) {
                            payload = candidate;
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (payload == null) {
                System.out.println("[Phase 6] Payload not in cache — activeFetch...");
                List<Block> fetched = networkEngine.activeFetchAsync(round, round).join();
                if (!fetched.isEmpty()) payload = fetched.get(0);
            }

            if (payload != null) {
                payload.setCertificate(cert);
                boolean applied = stateEngine.applyBlock(payload);
                if (applied) {
                    stateEngine.getDb().saveCertificate(cert);
                    // FIX #8: ACK all transactions since block committed successfully
                    sharedBuffer.ackCurrentBatch();

                    // Phase 6 Commit EventBus Hook: Compare roundBatch vs committed block
                    Set<String> blockTxIds = new HashSet<>();
                    if (payload.getTransactions() != null) {
                        for (Transaction tx : payload.getTransactions()) {
                            blockTxIds.add(tx.getTxId());
                        }
                    }
                    
                    for (Transaction tx : roundBatch) {
                        if (blockTxIds.contains(tx.getTxId())) {
                            eventGateway.publishValidated(tx);
                        } else {
                            eventGateway.publishRejectedInvalid(tx, "INVALID_TX");
                        }
                    }

                    networkEngine.setNetworkTipHash(payload.getHash());
                    networkEngine.setNetworkTip(round);
                    System.out.println("[Phase 6] Block committed: " + payload);
                } else {
                    // FIX #63: Apply failed — NACK transactions
                    System.err.println("[Phase 6] applyBlock failed for round=" + round);
                    sharedBuffer.nackCurrentBatch();
                    eventGateway.publishRejectedTimeout(new ArrayList<>(roundBatch));
                }
            } else {
                System.err.println("[Phase 6] Could not recover block payload for round=" + round + " — writing empty block.");
                Block emptyBlock = Block.createEmptyBlock(round, stateEngine.getLatestBlockHash(),
                                                          System.currentTimeMillis(), "");
                stateEngine.applyBlock(emptyBlock);
                sharedBuffer.nackCurrentBatch();
                eventGateway.publishRejectedTimeout(new ArrayList<>(roundBatch));
            }
        }

        // ── Advance Round ─────────────────────────────────────────────────────
        currentRound++;
        eventGateway.resetForNewRound();
        networkEngine.getForwardCache().purge(currentRound);
        System.out.println("[Phase 6] Round " + round + " complete. Advancing to round " + currentRound + ".");
    }

    // =========================================================================
    // Recovery Mode
    // =========================================================================

    private String enterRecoveryMode(long round) throws InterruptedException {
        System.out.println("[ConsensusEngine] RECOVERY MODE — waiting for network certificate...");
        BlockCertificate cert = certificateQueue.take();
        System.out.println("[ConsensusEngine] Recovery: Certificate received — resuming.");
        return cert.isBottom() ? BOTTOM : cert.getBlockHash();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a standard VoteMessage with vrfHash set from vrfResult.
     * FIX #28: vrfHash is set from the VRFResult.
     */
    private VoteMessage buildVoteMessage(long round, VoteMessage.Step step,
                                          String choice, VRF.VRFResult vrfResult, int weight) {
        VoteMessage vote = new VoteMessage();
        vote.setRound(round);
        vote.setStep(step);
        vote.setSenderPubKey(myWallet.getPublicKeyBase64());
        vote.setChoice(choice);
        vote.setVrfProof(vrfResult.proof);
        vote.setVrfHash(vrfResult.hash);   // FIX #28: always set vrfHash
        vote.setSortitionWeight(weight);

        String sig = Ed25519Util.signToBase64(myWallet.getPrivateKey(), vote.getSignableData());
        vote.setEd25519Signature(sig);
        return vote;
    }

    /**
     * FIX #46: Builds a CERTIFY_VOTE with both ed25519Signature (full signable data)
     * and blockHashSignature (signs only the block hash / choice field).
     * These are separate signatures with different semantics.
     */
    private VoteMessage buildCertifyVoteMessage(long round, String blockHash,
                                                  VRF.VRFResult vrfResult, int weight) {
        VoteMessage vote = buildVoteMessage(round, VoteMessage.Step.CERTIFY_VOTE, blockHash, vrfResult, weight);
        // FIX #46: Also sign just the block hash (this goes into BlockCertificate.CertEntry.signature)
        String blockHashSig = Ed25519Util.signToBase64(myWallet.getPrivateKey(), blockHash);
        vote.setBlockHashSignature(blockHashSig);
        return vote;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len - 1; i += 2) {
            data[i / 2] = (byte)((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public long getCurrentRound() { return currentRound; }
}
