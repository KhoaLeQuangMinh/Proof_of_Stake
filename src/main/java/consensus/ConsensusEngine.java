package consensus;

import app.Wallet;
import buffer.EventGateway;
import buffer.SharedBuffer;
import crypto.Ed25519Util;
import crypto.VRF;
import model.*;
import network.NetworkEngine;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import state.StateEngine;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ConsensusEngine — Component 4: The BBA* State Machine.
 *
 * Integration Plan §5 (Component 4: The Consensus Engine):
 *  - The main loop. Controls the Round clock and executes the BA* algorithm.
 *  - Replaces hardcoded Thread.sleep() with event-driven Dynamic EMA loops.
 *  - All 6 phases of the integrated plan are implemented here.
 *
 * The Main Loop:
 *  while (true) {
 *      handleCatchup();
 *      executeRound(current_round);
 *  }
 *
 * Phase Overview:
 *  Phase 1: Value Propose     — VRF lottery, if won: build block from SharedBuffer, gossip.
 *  Phase 2: Pre-Validation    — Collect proposals, simulate lowest VRF hash, pick Best_Proposal.
 *  Phase 3: Filter            — VRF lottery (1000 expected), if won: gossip SoftVote.
 *  Phase 4: Resolving Filter  — Count soft votes with EMA timeout. >680 weight = winner.
 *  Phase 5: BBA* Micro-Loop  — Binary agreement: gossip/count/coin until 680 weight decides.
 *  Phase 6: Halting Condition — Gossip CertifyVote, block on certificateQueue, applyBlock.
 */
public class ConsensusEngine {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** BBA* panic limit — maximum iterations before entering Recovery Mode. */
    private static final int  BBA_PANIC_LIMIT = 50;

    /** Sentinel for BOTTOM (empty block). */
    private static final String BOTTOM = VoteMessage.BOTTOM;

    /** Number of blocks in the past to look for stake (R-320). */
    private static final int CATCHUP_GAP_MACRO = 10;

    // -------------------------------------------------------------------------
    // Dependencies (injected via constructor)
    // -------------------------------------------------------------------------

    private final StateEngine   stateEngine;
    private final NetworkEngine networkEngine;
    private final SharedBuffer  sharedBuffer;
    private final EventGateway  eventGateway;
    private final Wallet        myWallet;

    /**
     * The Certificate Interrupt Queue.
     * Phase 6 blocks on this. NetworkEngine and certificate assembler offer to it.
     * This is the SystemInterrupt(CertificateEvent) mechanism.
     */
    private final LinkedBlockingQueue<BlockCertificate> certificateQueue;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private volatile long    currentRound = 0L;
    private final EMATimeout emaTimeout   = new EMATimeout();
    private volatile boolean running      = false;

    /**
     * Proposals received in Phase 2.
     * Maps BlockHash -> full Block (so we can fetch payload for simulation).
     */
    private final Map<String, Block> proposalCache = new HashMap<>();

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

    /**
     * Starts the consensus engine on a dedicated background daemon thread.
     */
    public void start() {
        running = true;
        Thread t = new Thread(this::runLoop, "ConsensusEngine");
        t.setDaemon(true);
        t.start();
        System.out.println("[ConsensusEngine] Started.");
    }

    public void stop() { running = false; }

    // -------------------------------------------------------------------------
    // Main Loop (§5.1)
    // -------------------------------------------------------------------------

    private void runLoop() {
        // Initialize current round from the ledger
        long ledgerRound = stateEngine.getLatestRound();
        currentRound = Math.max(0, ledgerRound + 1);
        System.out.println("[ConsensusEngine] Resuming from round " + currentRound);

        while (running) {
            try {
                handleCatchup();
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
    // Catchup Logic (§5.1 handleCatchup)
    // -------------------------------------------------------------------------

    /**
     * Integration Plan §5.1 handleCatchup():
     *  - Calculate Gap = Network_Tip - Local_Height.
     *  - If Gap < 0: Ignore (connected to lagging relay).
     *  - If Gap > 10 (Macro-Partition): Suspend live consensus; fetch catchpoint.
     *  - If 0 < Gap <= 10 (Micro-Partition): activeFetch missing blocks.
     *  - If Gap == 0: Exit catchup, enter executeRound().
     */
    private void handleCatchup() throws InterruptedException {
        long localHeight = stateEngine.getLatestRound();
        long networkTip  = networkEngine.getNetworkTip();
        long gap         = networkTip - localHeight;

        if (gap < 0) {
            return; // We are ahead of or equal to the observed tip — proceed normally
        }

        if (gap > CATCHUP_GAP_MACRO) {
            System.out.println("[ConsensusEngine] MACRO PARTITION — gap=" + gap +
                               ". Entering fast catchup.");
            // For the testbed, we activeFetch the missing blocks
            networkEngine.activeFetch(localHeight + 1, networkTip);
            // Wait for sync to complete
            Thread.sleep(3000);
        } else if (gap > 0) {
            System.out.println("[ConsensusEngine] Micro-partition — fetching " + gap + " missing blocks.");
            List<Block> fetchedBlocks = networkEngine.activeFetch(localHeight + 1, networkTip);
            for (Block b : fetchedBlocks) {
                stateEngine.applyBlock(b);
            }
        }

        // After catchup, update currentRound
        currentRound = Math.max(currentRound, stateEngine.getLatestRound() + 1);
    }

    // -------------------------------------------------------------------------
    // Round Execution (§5.2)
    // -------------------------------------------------------------------------

    /**
     * Executes a single full consensus round through all 6 phases.
     */
    private void executeRound(long round) throws InterruptedException {
        long roundStart = System.currentTimeMillis();
        proposalCache.clear();

        System.out.println("\n[ConsensusEngine] ========== ROUND " + round + " ==========");

        // VRF_Input = Ledger.getPreviousBlock().Seed (the chained Randomness Beacon)
        String vrfInput = stateEngine.getLatestSeed();

        // Phase 1: Value Propose
        phase1_ValuePropose(round, vrfInput);

        // Phase 2: Pre-Validation & Minimum Hash
        String bestProposal = phase2_PreValidation(round, vrfInput);

        // Phase 3: Filter (Proposer Committee Election)
        phase3_Filter(round, vrfInput, bestProposal);

        // Phase 4: Resolving Filter (Liveness Check)
        String bbaChoice = phase4_ResolvingFilter(round);

        // Phase 5: Binary Byzantine Agreement (BBA* Micro-Loop)
        String finalWinner = phase5_BbaStar(round, bbaChoice, vrfInput);

        // Phase 6: The Halting Condition (Certificate Assembly & Interrupt)
        phase6_HaltingCondition(round, finalWinner, vrfInput);

        // Record round duration for EMA
        long roundDuration = System.currentTimeMillis() - roundStart;
        emaTimeout.recordRoundDuration(roundDuration);

        System.out.println("[ConsensusEngine] Round " + round + " complete in " + roundDuration + "ms");
    }

    // =========================================================================
    // PHASE 1: Value Propose
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 1:
     *  - Run local VRF using Node_Private_Key and VRF_Input.
     *  - Use Binomial Distribution CDF to check if elected (expected=20 proposers).
     *  - If k > 0: pull transactions from SharedBuffer, construct Block, gossip.
     */
    private void phase1_ValuePropose(long round, String vrfInput) {
        System.out.println("[Phase 1] Value Propose — round=" + round);

        // Run local VRF
        VRF.VRFResult vrfResult = VRF.evaluate(myWallet.getPrivateKey(), vrfInput);

        // Sortition Math: Binomial Distribution (expected 20 proposers)
        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);

        byte[] vrfHashBytes = hexToBytes(vrfResult.hash);
        int myWeight = Sortition.getSortitionWeight(
            vrfHashBytes, myStake, totalStake, Sortition.EXPECTED_PROPOSERS
        );

        System.out.println("[Phase 1] Sortition: myStake=" + myStake + " totalStake=" + totalStake +
                           " weight=" + myWeight);

        if (myWeight <= 0) {
            System.out.println("[Phase 1] Not selected as proposer this round.");
            return;
        }

        System.out.println("[Phase 1] ✨ ELECTED AS PROPOSER (weight=" + myWeight + ")");

        // Pull transactions directly from the SharedBuffer
        List<Transaction> txBatch = new ArrayList<>(sharedBuffer.getCurrentBatch());
        System.out.println("[Phase 1] Pulled " + txBatch.size() + " transactions from SharedBuffer.");

        // Construct the Block
        Block block = constructBlock(round, txBatch, vrfResult);
        proposalCache.put(block.getHash(), block);

        // Gossip the block proposal
        NetworkMessage proposal = networkEngine.buildEnvelope(
            NetworkMessage.Type.PROPOSAL, round, block.toJson()
        );
        networkEngine.gossip(proposal);
        System.out.println("[Phase 1] Proposed block: " + block);
    }

    /**
     * Constructs a Block from a batch of transactions.
     * Sets all header fields including the new chained Seed.
     */
    private Block constructBlock(long round, List<Transaction> txs, VRF.VRFResult vrfResult) {
        BlockHeader header = new BlockHeader();
        header.setRound(round);
        header.setTimestamp(System.currentTimeMillis());
        header.setPreviousBlockHash(stateEngine.getLatestBlockHash());
        header.setProposerVRFProof(vrfResult.proof);
        header.setProposerPubKey(myWallet.getPublicKeyBase64());

        // New Seed = SHA-256(VRF_Proof || round) — the chained Randomness Beacon
        String newSeed = Ed25519Util.sha256Hex(vrfResult.proof + round);
        header.setSeed(newSeed);

        // Sign the header
        String headerSig = Ed25519Util.signToBase64(myWallet.getPrivateKey(), header.getSignableData());
        header.setEd25519Signature(headerSig);

        // Build the block
        Block block = new Block();
        block.setHeader(header);
        block.setTransactions(txs);

        // Compute Merkle root and block hash
        String merkleRoot = block.computeMerkleRoot();
        header.setTransactionMerkleRoot(merkleRoot);
        String blockHash = Ed25519Util.sha256Hex(header.getSignableData() + merkleRoot);
        header.setHash(blockHash);

        return block;
    }

    // =========================================================================
    // PHASE 2: Pre-Validation & Minimum Hash
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 2:
     *  - Start DynamicAdaptiveTimeout(EMA).
     *  - Collect proposals from ForwardCache.
     *  - Sort by VRF hash (lowest first — cryptographic leader election).
     *  - Simulate payload of lowest hash. If fails: ban proposer, try next.
     *  - Best_Proposal = lowest valid hash that passes simulation.
     *  - If 0 valid proposals: Best_Proposal = BOTTOM.
     */
    private String phase2_PreValidation(long round, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 2] Pre-Validation — collecting proposals...");

        long timeout = emaTimeout.getNextTimeoutMs();
        long deadline = System.currentTimeMillis() + timeout;

        // Collect all proposals from ForwardCache using EMA timeout
        Map<String, VoteMessage> proposals = new HashMap<>();
        while (System.currentTimeMillis() < deadline) {
            if (!certificateQueue.isEmpty()) break; // Certificate interrupt!

            long remaining = deadline - System.currentTimeMillis();
            VoteMessage vote = networkEngine.getForwardCache()
                                           .poll(round, VoteMessage.Step.SOFT_VOTE,
                                                 Math.min(remaining, 200));
            if (vote != null && vote.getChoice() != null && !vote.getChoice().equals(BOTTOM)) {
                proposals.put(vote.getChoice(), vote);
            }
        }

        System.out.println("[Phase 2] Collected " + proposals.size() + " proposals.");

        if (proposals.isEmpty()) {
            System.out.println("[Phase 2] No proposals received — Best_Proposal = BOTTOM");
            return BOTTOM;
        }

        // Sort valid proposals by VRF hash (lowest = leader)
        List<Map.Entry<String, VoteMessage>> sortedProposals = new ArrayList<>(proposals.entrySet());
        sortedProposals.sort((a, b) -> {
            String hashA = a.getValue().getVrfProof();
            String hashB = b.getValue().getVrfProof();
            return hashA.compareTo(hashB); // lexicographic = same as numeric for hex
        });

        // Simulate proposals in order (lowest VRF hash first)
        for (Map.Entry<String, VoteMessage> entry : sortedProposals) {
            String    blockHash = entry.getKey();
            VoteMessage vote    = entry.getValue();

            // Verify the proposer's VRF stake claim
            int proposerWeight = stateEngine.verifyVRFStake(
                vote.getSenderPubKey(),
                vote.getVrfProof(),
                vote.getChoice(),
                vrfInput, round,
                Sortition.EXPECTED_PROPOSERS
            );

            if (proposerWeight <= 0) {
                System.out.println("[Phase 2] Skipping unelected proposer: " + vote.getSenderPubKey().substring(0, 8));
                continue;
            }

            // Fetch the full block payload from cache or network
            Block proposedBlock = proposalCache.get(blockHash);

            if (proposedBlock == null) {
                System.out.println("[Phase 2] Block payload not in cache for hash=" +
                                   blockHash.substring(0, 8) + " — skipping.");
                continue;
            }

            // Simulate the block — if fails, ban proposer and try next
            if (!stateEngine.simulateBlock(proposedBlock, round)) {
                System.out.println("[Phase 2] Simulation FAILED for proposal " +
                                   blockHash.substring(0, 8) + " — banning proposer.");
                networkEngine.banProposer(vote.getSenderPubKey());
                continue;
            }

            System.out.println("[Phase 2] Best_Proposal = " + blockHash.substring(0, 8));
            return blockHash;
        }

        // No valid proposals survived simulation
        System.out.println("[Phase 2] All proposals failed simulation — Best_Proposal = BOTTOM");
        return BOTTOM;
    }

    // =========================================================================
    // PHASE 3: Filter (Proposer Committee Election)
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 3:
     *  - Run local VRF (same VRF_Input as Phase 1).
     *  - Call verifyVRFStake to check if elected (expected committee = 1000).
     *  - If won: gossip VoteMessage(SOFT_VOTE, Best_Proposal, weight).
     */
    private void phase3_Filter(long round, String vrfInput, String bestProposal) {
        System.out.println("[Phase 3] Filter — checking election...");

        // Run VRF (same input as Phase 1 — both phases use the same VRF_Input)
        VRF.VRFResult vrfResult = VRF.evaluate(myWallet.getPrivateKey(), vrfInput);

        // Sortition: expected 1000 committee members
        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);

        byte[] vrfHashBytes = hexToBytes(vrfResult.hash);
        int myWeight = Sortition.getSortitionWeight(
            vrfHashBytes, myStake, totalStake, Sortition.EXPECTED_VOTERS
        );

        System.out.println("[Phase 3] Sortition weight=" + myWeight);

        if (myWeight <= 0) {
            System.out.println("[Phase 3] Not elected to voter committee.");
            return;
        }

        System.out.println("[Phase 3] ✨ ELECTED AS VOTER (weight=" + myWeight + ") — voting for: " +
                           bestProposal.substring(0, Math.min(8, bestProposal.length())));

        // Build and gossip the SoftVote
        VoteMessage vote = buildVoteMessage(round, VoteMessage.Step.SOFT_VOTE,
                                             bestProposal, vrfResult, myWeight);
        NetworkMessage msg = networkEngine.buildEnvelope(
            NetworkMessage.Type.SOFT_VOTE, round, vote.toJson()
        );
        networkEngine.gossip(msg);
    }

    // =========================================================================
    // PHASE 4: Resolving Filter (Liveness Check)
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 4:
     *  - Start DynamicAdaptiveTimeout(EMA).
     *  - Count votes with equivocation detection.
     *  - Dynamic Threshold: vote passes if hash hits > 68% of expected committee.
     *  - If any hash hits threshold before timeout: BBA_Choice = Winning_Hash.
     *  - If timeout fires: BBA_Choice = BOTTOM.
     */
    private String phase4_ResolvingFilter(long round) throws InterruptedException {
        System.out.println("[Phase 4] Resolving Filter — counting soft votes...");

        long timeout  = emaTimeout.getNextTimeoutMs();
        long deadline = System.currentTimeMillis() + timeout;

        // Split vote tracker: blockHash -> total weight
        Map<String, Integer> hashWeights = new HashMap<>();

        // Equivocation detection: senderPubKey -> first hash they voted for
        Map<String, String> equivocationTracker = new HashMap<>();

        while (System.currentTimeMillis() < deadline) {
            // Check for early certificate interrupt
            if (!certificateQueue.isEmpty()) {
                System.out.println("[Phase 4] Certificate interrupt detected — advancing to Phase 6.");
                return BOTTOM; // Certificate will be handled in Phase 6
            }

            long remaining = deadline - System.currentTimeMillis();
            VoteMessage vote = networkEngine.getForwardCache()
                                           .poll(round, VoteMessage.Step.SOFT_VOTE,
                                                 Math.min(remaining, 100));
            if (vote == null) continue;

            // Equivocation detection
            String prevChoice = equivocationTracker.putIfAbsent(vote.getSenderPubKey(), vote.getChoice());
            if (prevChoice != null && !prevChoice.equals(vote.getChoice())) {
                System.out.println("[Phase 4] Equivocation detected from " +
                                   vote.getSenderPubKey().substring(0, 8) + " — dropping both votes.");
                // Remove the previously counted vote for this sender
                hashWeights.merge(prevChoice, -vote.getSortitionWeight(), Integer::sum);
                continue; // Drop current equivocating vote too
            }

            // Verify VRF stake (deferred from NetworkEngine — heavy math done here)
            // For the testbed, we trust the sortitionWeight as declared
            int weight = Math.max(0, vote.getSortitionWeight());

            String hash = vote.getChoice();
            if (hash != null) {
                int newWeight = hashWeights.merge(hash, weight, Integer::sum);

                // Check if this hash has crossed the 68% supermajority threshold
                if (Sortition.hasReachedThreshold(newWeight)) {
                    System.out.println("[Phase 4] SUPERMAJORITY reached for " +
                                       hash.substring(0, Math.min(8, hash.length())) +
                                       " (weight=" + newWeight + " > " + Sortition.VOTE_THRESHOLD + ")");
                    return hash;
                }
            }
        }

        // Timeout fired — no supermajority reached (split vote or partition)
        System.out.println("[Phase 4] Timeout — no supermajority. BBA_Choice = BOTTOM");
        return BOTTOM;
    }

    // =========================================================================
    // PHASE 5: Binary Byzantine Agreement (BBA* Micro-Loop)
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 5:
     *  Loop:
     *    Step A (Gossip):  Broadcast BBA_Choice.
     *    Step B (Count):   >680 weight for any hash -> Final_Winner. Break.
     *    Step C (Coin):    If deadlock: Common Coin LSB determines next BBA_Choice.
     *  Panic Limit: 50 iterations. If exceeded, enter RecoveryMode().
     */
    private String phase5_BbaStar(long round, String bbaChoice, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 5] BBA* Micro-Loop — initial choice: " +
                           bbaChoice.substring(0, Math.min(8, bbaChoice.length())));

        String lastKnownHash = BOTTOM; // Track the last seen non-bottom hash for coin reset

        int panicCounter = 0;

        while (running) {
            panicCounter++;

            // ── Panic Limit ──────────────────────────────────────────────────
            if (panicCounter > BBA_PANIC_LIMIT) {
                System.out.println("[Phase 5] PANIC LIMIT reached (" + BBA_PANIC_LIMIT +
                                   " iterations). Entering Recovery Mode.");
                return enterRecoveryMode(round);
            }

            // ── Step A: Gossip BBA_Choice ──────────────────────────────────
            VRF.VRFResult vrfResult = VRF.evaluate(myWallet.getPrivateKey(), vrfInput + "BBA" + panicCounter);
            long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
            long totalStake = stateEngine.getOnlineStake(round);
            byte[] vrfHashBytes = hexToBytes(vrfResult.hash);
            int myWeight = Sortition.getSortitionWeight(vrfHashBytes, myStake, totalStake, Sortition.EXPECTED_VOTERS);

            if (myWeight > 0) {
                VoteMessage bbaVote = buildVoteMessage(round, VoteMessage.Step.BBA_GOSSIP,
                                                       bbaChoice, vrfResult, myWeight);
                NetworkMessage msg = networkEngine.buildEnvelope(
                    NetworkMessage.Type.BBA_GOSSIP, round, bbaVote.toJson()
                );
                networkEngine.gossip(msg);
            }

            // ── Step B: Count BBA Votes ────────────────────────────────────
            long timeout  = emaTimeout.getNextTimeoutMs();
            long deadline = System.currentTimeMillis() + timeout;

            Map<String, Integer> hashWeights    = new HashMap<>();
            Map<String, String>  equivocations  = new HashMap<>();

            while (System.currentTimeMillis() < deadline) {
                // Check for certificate interrupt — if certificate arrived, BBA* is done
                if (!certificateQueue.isEmpty()) {
                    System.out.println("[Phase 5] Certificate interrupt — BBA* complete.");
                    return bbaChoice; // Phase 6 will handle the actual certificate
                }

                long remaining = deadline - System.currentTimeMillis();
                VoteMessage vote = networkEngine.getForwardCache()
                                               .poll(round, VoteMessage.Step.BBA_GOSSIP,
                                                     Math.min(remaining, 100));
                if (vote == null) continue;

                // Equivocation detection
                String prevChoice = equivocations.putIfAbsent(vote.getSenderPubKey(), vote.getChoice());
                if (prevChoice != null && !prevChoice.equals(vote.getChoice())) {
                    hashWeights.merge(prevChoice, -vote.getSortitionWeight(), Integer::sum);
                    continue;
                }

                String hash = vote.getChoice();
                if (hash != null) {
                    if (!hash.equals(BOTTOM)) lastKnownHash = hash;
                    int newWeight = hashWeights.merge(hash, vote.getSortitionWeight(), Integer::sum);

                    // Check supermajority for any specific hash (non-bottom)
                    if (!hash.equals(BOTTOM) && Sortition.hasReachedThreshold(newWeight)) {
                        System.out.println("[Phase 5] BBA* converged on block hash: " +
                                           hash.substring(0, 8) + " (weight=" + newWeight + ")");
                        return hash; // Final_Winner = block hash
                    }

                    // Check supermajority for BOTTOM
                    if (hash.equals(BOTTOM) && Sortition.hasReachedThreshold(newWeight)) {
                        System.out.println("[Phase 5] BBA* converged on BOTTOM (weight=" + newWeight + ")");
                        return BOTTOM; // Final_Winner = Bottom
                    }
                }
            }

            // ── Step C: Common Coin Deadlock Breaker ───────────────────────
            System.out.println("[Phase 5] Deadlock detected — running Common Coin (iteration=" + panicCounter + ")");
            String globalCoin = runCommonCoin(round, panicCounter);

            // LSB parity determines BBA_Choice for next iteration
            int parity = VRF.getCoinParity(globalCoin);
            if (parity == 0) {
                // Even: BBA_Choice = Hash X (the last known non-bottom block hash)
                bbaChoice = lastKnownHash.equals(BOTTOM) ? BOTTOM : lastKnownHash;
                System.out.println("[Phase 5] Coin=EVEN → BBA_Choice = " +
                                   bbaChoice.substring(0, Math.min(8, bbaChoice.length())));
            } else {
                // Odd: BBA_Choice = BOTTOM
                bbaChoice = BOTTOM;
                System.out.println("[Phase 5] Coin=ODD → BBA_Choice = BOTTOM");
            }
        }

        return BOTTOM;
    }

    /**
     * Runs the Common Coin sub-protocol (Phase 5 Step C).
     * Each eligible node gossips its VRF hash. The globally lowest VRF hash
     * is the Common Coin — everyone sees the same value.
     */
    private String runCommonCoin(long round, int iteration) throws InterruptedException {
        // Generate this node's coin VRF
        VRF.VRFResult coinVRF = VRF.generateCoin(myWallet.getPrivateKey(), round, iteration);

        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);
        byte[] coinHashBytes = hexToBytes(coinVRF.hash);
        int myWeight = Sortition.getSortitionWeight(coinHashBytes, myStake, totalStake, Sortition.EXPECTED_VOTERS);

        if (myWeight > 0) {
            // Gossip coin VRF hash as COMMON_COIN message
            VoteMessage coinMsg = buildVoteMessage(round, VoteMessage.Step.COMMON_COIN,
                                                    coinVRF.hash, coinVRF, myWeight);
            NetworkMessage msg = networkEngine.buildEnvelope(
                NetworkMessage.Type.COMMON_COIN, round, coinMsg.toJson()
            );
            networkEngine.gossip(msg);
        }

        // Collect coins from the network
        long deadline = System.currentTimeMillis() + emaTimeout.getNextTimeoutMs();
        List<String> validCoins = new ArrayList<>();

        // Include our own coin if we have stake
        if (myStake > 0) validCoins.add(coinVRF.hash);

        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            VoteMessage coin = networkEngine.getForwardCache()
                                           .poll(round, VoteMessage.Step.COMMON_COIN,
                                                 Math.min(remaining, 100));
            if (coin != null && coin.getChoice() != null) {
                // Verify stake (simplified: trust declared weight)
                if (coin.getSortitionWeight() > 0) {
                    validCoins.add(coin.getChoice());
                }
            }
        }

        if (validCoins.isEmpty()) {
            System.out.println("[Phase 5] No valid coins received — using own coin as fallback.");
            return coinVRF.hash;
        }

        // Global_Coin = Lowest VRF Hash (lexicographically)
        String globalCoin = Collections.min(validCoins);
        System.out.println("[Phase 5] Global Coin = " + globalCoin.substring(0, 16) + "...");
        return globalCoin;
    }

    // =========================================================================
    // PHASE 6: The Halting Condition
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 6:
     *  1. Gossip CertifyVote(Final_Winner).
     *  2. Block on certificateQueue (SystemInterrupt).
     *  3. Once triggered:
     *     - Bottom: Create Empty Block, write to ledger, fire TXN_REJECTED(Timeout).
     *     - Block:  Fetch payload, stateEngine.applyBlock (fires TXN_VALIDATED).
     *  4. Current_Round++.
     *  5. Purge ForwardCache for old rounds.
     */
    private void phase6_HaltingCondition(long round, String finalWinner, String vrfInput) throws InterruptedException {
        System.out.println("[Phase 6] Halting Condition — finalWinner=" +
                           finalWinner.substring(0, Math.min(8, finalWinner.length())));

        // Gossip our CertifyVote
        VRF.VRFResult certVRF = VRF.evaluate(myWallet.getPrivateKey(), vrfInput + "CERT" + round);
        long myStake    = stateEngine.getAddressStake(myWallet.getPublicKeyBase64(), round);
        long totalStake = stateEngine.getOnlineStake(round);
        byte[] certHashBytes = hexToBytes(certVRF.hash);
        int myWeight = Sortition.getSortitionWeight(certHashBytes, myStake, totalStake, Sortition.EXPECTED_VOTERS);

        if (myWeight > 0) {
            // Sign the block hash as the CertifyVote payload
            String hashSig = Ed25519Util.signToBase64(myWallet.getPrivateKey(), finalWinner);
            VoteMessage certVote = buildVoteMessage(round, VoteMessage.Step.CERTIFY_VOTE,
                                                     finalWinner, certVRF, myWeight);
            certVote.setEd25519Signature(hashSig); // Override: certify vote signs the block hash
            NetworkMessage msg = networkEngine.buildEnvelope(
                NetworkMessage.Type.CERTIFY_VOTE, round, certVote.toJson()
            );
            networkEngine.gossip(msg);
            System.out.println("[Phase 6] CertifyVote gossiped (weight=" + myWeight + ")");
        }

        // ── Block and wait for SystemInterrupt(CertificateEvent) ──────────────
        System.out.println("[Phase 6] Waiting for BlockCertificate (blocking on certificateQueue)...");
        BlockCertificate cert = certificateQueue.poll(30, TimeUnit.SECONDS);

        if (cert == null) {
            // Absolute timeout — very abnormal. Treat as Bottom.
            System.out.println("[Phase 6] Certificate timeout after 30s — treating as BOTTOM.");
            cert = new BlockCertificate();
            cert.setRound(round);
            cert.setBlockHash(BOTTOM);
        }

        System.out.println("[Phase 6] Certificate received: " + cert);

        // ── Handle Bottom (Empty Block) ───────────────────────────────────────
        if (cert.isBottom() || BOTTOM.equals(cert.getBlockHash())) {
            System.out.println("[Phase 6] BOTTOM round — creating empty block.");

            Block emptyBlock = Block.createEmptyBlock(
                round,
                stateEngine.getLatestBlockHash(),
                System.currentTimeMillis(),
                myWallet.getPublicKeyBase64()
            );
            stateEngine.applyBlock(emptyBlock);

            // Phase 6 Timeout Hook: fire TXN_REJECTED(Timeout) for all staged txs
            // via EventGateway ONLY for transactions not already resolved in Phase 2
            eventGateway.publishRejectedTimeout(new ArrayList<>(sharedBuffer.getCurrentBatch()));

        } else {
            // ── Handle Successful Block ───────────────────────────────────────
            String blockHash = cert.getBlockHash();
            Block payload = proposalCache.get(blockHash);

            if (payload == null) {
                // Network Resilience: fetch missing payload
                System.out.println("[Phase 6] Payload not in cache — activeFetch...");
                List<Block> fetched = networkEngine.activeFetch(round, round);
                if (!fetched.isEmpty()) {
                    payload = fetched.get(0);
                }
            }

            if (payload != null) {
                payload.setCertificate(cert);
                stateEngine.applyBlock(payload);
                stateEngine.getDb().saveCertificate(cert);
            } else {
                System.err.println("[Phase 6] Could not recover block payload for round " + round);
                // Fallback: write empty block to avoid chain halt
                Block emptyBlock = Block.createEmptyBlock(round, stateEngine.getLatestBlockHash(),
                                                          System.currentTimeMillis(), "");
                stateEngine.applyBlock(emptyBlock);
            }
        }

        // ── Advance Round ─────────────────────────────────────────────────────
        currentRound++;
        eventGateway.resetForNewRound(); // Clear deduplication cache for new round
        networkEngine.getForwardCache().purge(currentRound); // Garbage collect old messages

        System.out.println("[Phase 6] Round " + round + " complete. Advancing to round " + currentRound + ".");
    }

    // =========================================================================
    // Recovery Mode (BBA* Panic Limit Exceeded)
    // =========================================================================

    /**
     * Integration Plan §5.2 Phase 5:
     *  "If panic_counter > 50, enterRecoveryMode():
     *   Suspending the Consensus Thread entirely. Attach a listener to
     *   SystemInterrupt(CertificateEvent) to wake the thread up and force
     *   it into handleCatchup() when the network heals."
     */
    private String enterRecoveryMode(long round) throws InterruptedException {
        System.out.println("[ConsensusEngine] RECOVERY MODE — waiting for network certificate...");

        // Block indefinitely on the certificate queue
        // The NetworkEngine will offer a certificate when the network heals
        BlockCertificate cert = certificateQueue.take();

        System.out.println("[ConsensusEngine] Recovery: Certificate received — resuming.");
        return cert.isBottom() ? BOTTOM : cert.getBlockHash();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds a fully-signed VoteMessage. */
    private VoteMessage buildVoteMessage(long round, VoteMessage.Step step,
                                          String choice, VRF.VRFResult vrfResult, int weight) {
        VoteMessage vote = new VoteMessage();
        vote.setRound(round);
        vote.setStep(step);
        vote.setSenderPubKey(myWallet.getPublicKeyBase64());
        vote.setChoice(choice);
        vote.setVrfProof(vrfResult.proof);
        vote.setSortitionWeight(weight);

        // Sign the vote message
        String sig = Ed25519Util.signToBase64(myWallet.getPrivateKey(), vote.getSignableData());
        vote.setEd25519Signature(sig);

        return vote;
    }

    /** Converts a hex string to a byte array. */
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
