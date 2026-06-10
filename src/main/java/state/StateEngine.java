package state;

import buffer.EventGateway;
import consensus.Sortition;
import crypto.Ed25519Util;
import crypto.VRF;
import model.Block;
import model.BlockCertificate;
import model.Transaction;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * StateEngine — Component 3: The Ledger Manager.
 *
 * Integration Plan §4 (Component 3: The State Engine):
 *  - Manages the SQLite database and VRF math.
 *  - Routes all EventBus hooks through the Singleton EventGateway.
 *  - Uses the PendingStateOverlay for disk-free block simulation.
 *
 * Fixes applied:
 *  FIX #42/#43: verifyCertificate() now passes entry.vrfHash (not entry.voterPubKey)
 *               to verifyVRFStake(). The vrfHash field is the SHA-512(vrfProof) hex string.
 *               Additionally, the VRF input for CERTIFY_VOTE is "CERTIFY:" + round,
 *               not the block seed (which is only used in Phase 1/3).
 *
 *  FIX #58: simulateBlock() now also validates the block header before simulating txs:
 *           - TransactionMerkleRoot must match computed Merkle root of transactions.
 *
 *  FIX #59: applyBlock() validates the block again via a re-simulation before writing.
 *           Rejects if the block fails simulation at commit time (e.g. stale state).
 *
 *  FIX #60: simulateBlock() enforces validity window: firstValid <= round <= lastValid.
 *           Already in PendingStateOverlay.simulate(), but now also double-checked here
 *           before calling overlay.simulate() to allow a specific rejection reason.
 *
 *  FIX #61: simulateBlock() enforces MAX_TX_PER_BLOCK = 10. Blocks with more than
 *           10 transactions are rejected outright (per integrated plan §3.1).
 *
 *  FIX #63: applyBlock() result propagated: returns false and does NOT fire
 *           TXN_VALIDATED if db.saveBlock() fails for any reason.
 */
public class StateEngine {

    /** R-320 lookback: how many blocks back to look for stake snapshots. */
    private static final int LOOKBACK_WINDOW = 320;

    /** Expected committee size for certify votes (certificate verification threshold). */
    private static final int EXPECTED_COMMITTEE = 1000;

    /** FIX #61: Maximum transactions allowed per block (per integrated plan §3.1). */
    private static final int MAX_TX_PER_BLOCK = 10;

    private final DatabaseManager db;
    private final EventGateway    eventGateway;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public StateEngine(DatabaseManager db, EventGateway eventGateway) {
        this.db           = db;
        this.eventGateway = eventGateway;
    }

    // -------------------------------------------------------------------------
    // Stake Queries (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Returns the total online stake at the R-320 lookback round.
     * FIX #3/#4 (via DatabaseManager): Now finds genesis stake data.
     */
    public long getOnlineStake(long currentRound) {
        long lookbackRound = Math.max(0, currentRound - LOOKBACK_WINDOW);
        long stake = db.getOnlineStakeAtRound(lookbackRound);
        return Math.max(1L, stake); // Avoid division-by-zero in Sortition
    }

    /**
     * Returns the stake of a specific address at the R-320 lookback round.
     */
    public long getAddressStake(String pubKey, long currentRound) {
        long lookbackRound = Math.max(0, currentRound - LOOKBACK_WINDOW);
        return db.getAddressStakeAtRound(pubKey, lookbackRound);
    }

    // -------------------------------------------------------------------------
    // VRF Stake Verification (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Verifies the sortition claim of a message sender.
     *
     * FIX #30: Uses vrfHash (not vrfProof) as the argument to VRF.verify().
     *          VRF.verify() requires: Ed25519_Verify(pubKey, vrfInput, vrfProof)
     *          AND SHA-512(vrfProof) == vrfHash.
     *          Previously this was called with vrfProof passed as both proof and expectedHash.
     *
     * @param senderPubKey      Base64-encoded Ed25519 public key.
     * @param vrfProof          Base64-encoded VRF proof (Ed25519 signature over vrfInput).
     * @param vrfHash           The 128-char hex VRF output hash (SHA-512 of vrfProof).
     * @param vrfInput          The VRF input string (previous block seed for Phase 1/3).
     * @param currentRound      The current round (for R-320 lookback).
     * @param expectedCommittee Expected committee size (20 for proposers, 1000 for voters).
     * @return Number of sortition seats this sender won (0 = not elected).
     */
    public int verifyVRFStake(String senderPubKey, String vrfProof, String vrfHash,
                               String vrfInput, long currentRound, int expectedCommittee) {
        try {
            // Step 1: Verify the VRF proof (Ed25519 sig) AND that SHA-512(proof) == vrfHash
            Ed25519PublicKeyParameters pubKey = Ed25519Util.decodePublicKey(senderPubKey);
            // FIX #30: vrfHash is the expected hash, vrfProof is the proof bytes
            if (!VRF.verify(pubKey, vrfInput, vrfProof, vrfHash)) {
                System.out.println("[StateEngine] verifyVRFStake REJECT - VRF.verify failed. vrfInput=" + vrfInput + " vrfHash=" + vrfHash);
                return 0; // Invalid proof
            }

            // Step 2: Look up stake at R-320 (Flash Loan defense)
            long myStake    = getAddressStake(senderPubKey, currentRound);
            long totalStake = getOnlineStake(currentRound);

            if (myStake <= 0) {
                System.out.println("[StateEngine] verifyVRFStake REJECT - myStake <= 0");
                return 0; // Not staked
            }

            // Step 3: Binomial Distribution to determine seats won
            byte[] hashBytes = hexToBytes(vrfHash);
            int weight = Sortition.getSortitionWeight(hashBytes, myStake, totalStake, expectedCommittee);
            if (weight == 0) {
                System.out.println("[StateEngine] verifyVRFStake REJECT - weight is 0 (sortition fail)");
            }
            return weight;

        } catch (Exception e) {
            System.err.println("[StateEngine] verifyVRFStake error: " + e.getMessage());
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Certificate Verification (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Verifies that a BlockCertificate is mathematically valid.
     *
     * FIX #42: Now passes entry.vrfHash (not entry.voterPubKey) to verifyVRFStake().
     *          This is the critical fix — voterPubKey is a 32-byte Base64 string,
     *          not a SHA-512 hex string. Passing it as vrfHash caused VRF.verify()
     *          to always return false since SHA-512(vrfProof) never equals a pubKey.
     *
     * FIX #43: The VRF input for CERTIFY_VOTE is "CERTIFY:" + round, not the prev seed.
     *          Phase 6 certify votes use a different VRF input domain separator to ensure
     *          they cannot be confused with Phase 1/3 VRF outputs.
     *
     * @param cert         The BlockCertificate to verify.
     * @param currentRound The round of this certificate.
     * @return true if the certificate represents a genuine >2/3 supermajority.
     */
    public boolean verifyCertificate(BlockCertificate cert, long currentRound) {
        if (cert == null || cert.getVotes() == null || cert.getVotes().isEmpty()) {
            return false;
        }

        // FIX #43: CERTIFY_VOTE VRF input = "CERTIFY:" + round (not the block seed)
        String certifyVrfInput = "CERTIFY:" + currentRound;

        int totalVerifiedWeight = 0;

        for (BlockCertificate.CertEntry entry : cert.getVotes()) {
            if (entry.voterPubKey == null || entry.vrfProof == null || entry.vrfHash == null) {
                continue; // Skip malformed entries
            }

            // FIX #42: Pass entry.vrfHash (not entry.voterPubKey) as the expected VRF hash
            int weight = verifyVRFStake(
                entry.voterPubKey,
                entry.vrfProof,
                entry.vrfHash,          // FIX #42: was entry.voterPubKey (wrong field)
                certifyVrfInput,        // FIX #43: CERTIFY domain separator
                currentRound,
                EXPECTED_COMMITTEE
            );

            // FIX #46: Verify the blockHashSignature (not the full vote signature)
            boolean sigValid = false;
            try {
                byte[] expectedHashBytes;
                if (model.Block.BOTTOM_HASH.equals(cert.getBlockHash())) {
                    expectedHashBytes = model.VoteMessage.BOTTOM.getBytes();
                } else {
                    expectedHashBytes = hexToBytes(cert.getBlockHash());
                }
                
                byte[] sigBytes = java.util.Base64.getDecoder().decode(entry.signature);
                sigValid = crypto.Ed25519Util.verify(
                    crypto.Ed25519Util.decodePublicKey(entry.voterPubKey),
                    expectedHashBytes,
                    sigBytes
                );
            } catch (Exception e) {
                // Invalid signature encoding
            }

            if (weight > 0 && sigValid) {
                totalVerifiedWeight += weight;
            }
        }

        // Must strictly exceed 68% of expected committee (>680 out of 1000)
        return totalVerifiedWeight > (int)(0.68 * EXPECTED_COMMITTEE);
    }

    // -------------------------------------------------------------------------
    // Block Simulation (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Simulates a proposed block in RAM without touching SQLite.
     *
     * FIX #58: Now validates the block header before simulating transactions:
     *          - Rejects if TransactionMerkleRoot doesn't match the actual txs.
     *
     * FIX #60: Validity window is enforced per-transaction (also in overlay,
     *          but explicitly logged here for a clear rejection reason).
     *
     * FIX #61: Enforces MAX_TX_PER_BLOCK = 10. Rejects blocks with > 10 txs.
     *
     * @param block        The proposed block to simulate.
     * @param currentRound The current consensus round.
     * @return true if the block header is valid and all transactions pass.
     */
    public boolean simulateBlock(Block block, long currentRound) {
        if (block == null) {
            return false;
        }

        // Bug U Fix: Cryptographically verify the proposer's VRF weight
        String proposerPubKey = block.getHeader().getProposerPubKey();
        String proposerVrfProof = block.getHeader().getProposerVRFProof();
        String previousSeed = getLatestSeed();

        int proposerWeight = 0;
        try {
            byte[] proofBytes = java.util.Base64.getDecoder().decode(proposerVrfProof);
            String proposerVrfHash = crypto.Ed25519Util.sha512Hex(proofBytes);
            proposerWeight = verifyVRFStake(
                proposerPubKey,
                proposerVrfProof,
                proposerVrfHash,
                previousSeed,
                currentRound,
                consensus.Sortition.EXPECTED_PROPOSERS
            );
        } catch (Exception e) {
            System.err.println("[StateEngine] Simulation REJECT — Malformed VRF Proof");
            return false;
        }

        if (proposerWeight <= 0) {
            System.err.println("[StateEngine] Simulation REJECT — Proposer has ZERO weight (VRF Forgery).");
            return false;
        }

        if (block.isEmpty()) {
            return true; // Empty blocks from verified proposers are valid
        }

        List<Transaction> txs = block.getTransactions();
        if (txs == null || txs.isEmpty()) {
            return true;
        }

        // FIX #61: Enforce max 10 transactions per block
        if (txs.size() > MAX_TX_PER_BLOCK) {
            System.out.println("[StateEngine] Simulation REJECT — too many txs: " + txs.size());
            return false;
        }

        // FIX #58: Validate TransactionMerkleRoot in the block header
        String claimedMerkleRoot   = block.getHeader().getTransactionMerkleRoot();
        String computedMerkleRoot  = block.computeMerkleRoot();
        if (!computedMerkleRoot.equals(claimedMerkleRoot)) {
            System.out.println("[StateEngine] Simulation REJECT — Merkle root mismatch." +
                               " claimed=" + claimedMerkleRoot.substring(0, 8) +
                               " computed=" + computedMerkleRoot.substring(0, 8));
            return false;
        }

        // Collect all unique pubKeys for overlay pre-loading
        List<String> addresses = new ArrayList<>();
        for (Transaction tx : txs) {
            if (tx.getSenderPubKey() != null)   addresses.add(tx.getSenderPubKey());
            if (tx.getReceiverPubKey() != null) addresses.add(tx.getReceiverPubKey());
        }

        // Load balances into the Pending State Overlay (RAM only)
        PendingStateOverlay overlay = new PendingStateOverlay();
        overlay.loadFromDB(db, addresses);

        boolean allValid = true;

        for (Transaction tx : txs) {
            // FIX #62: Null type guard (already in overlay, but log it here too)
            if (tx.getType() == null) {
                System.out.println("[StateEngine] Simulation REJECT — null tx type");
                allValid = false;
                continue;
            }

            // FIX #60: Explicit validity window check with logging
            if (currentRound < tx.getFirstValid() || currentRound > tx.getLastValid()) {
                System.out.println("[StateEngine] Simulation REJECT — tx outside validity window: " +
                                   tx.getTxId() + " round=" + currentRound +
                                   " [" + tx.getFirstValid() + "," + tx.getLastValid() + "]");
                allValid = false;
                continue;
            }

            // Check for cross-block duplicate txId (replay attack)
            if (db.transactionExists(tx.getTxId())) {
                System.out.println("[StateEngine] Simulation REJECT — duplicate txId: " + tx.getTxId());
                allValid = false;
                continue;
            }

            // Verify Ed25519 signature (txId NOT in signable data per FIX #55)
            boolean sigValid = Ed25519Util.verifyFromBase64(
                tx.getSenderPubKey(),
                tx.getSignableData(),
                tx.getEd25519Signature()
            );
            if (!sigValid) {
                System.out.println("[StateEngine] Simulation REJECT — invalid signature: " + tx.getTxId());
                allValid = false;
                continue;
            }

            // Simulate the transaction against the in-memory overlay
            if (!overlay.simulate(tx, currentRound, block.getHeader().getProposerPubKey())) {
                System.out.println("[StateEngine] Simulation REJECT — math/balance fail: " + tx.getTxId());
                allValid = false;
                // Continue checking remaining transactions
            }
        }

        return allValid;
    }

    // -------------------------------------------------------------------------
    // Block Application (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Permanently commits a certified block to the SQLite ledger.
     *
     * FIX #59: Re-simulates the block before writing to the DB.
     *          This guards against stale-state proposals that passed simulation
     *          at Phase 2 but have since become invalid (e.g. conflicting block committed first).
     *
     * FIX #63: Returns false and does NOT fire TXN_VALIDATED if db.saveBlock() fails.
     *          Previously there was no check on saveBlock()'s return value.
     *
     * @param block The certified block to commit permanently.
     * @return true if the block was successfully applied.
     */
    public boolean applyBlock(Block block) {
        if (block == null) return false;

        // Chain validation: ensure this block extends the current tip
        String expectedPrevHash = db.getLatestBlockHash();
        String actualPrevHash   = block.getHeader().getPreviousBlockHash();
        if (!expectedPrevHash.equals(actualPrevHash)) {
            System.err.println("[StateEngine] applyBlock REJECTED — broken chain!" +
                               " expected prev=" + expectedPrevHash.substring(0, Math.min(8, expectedPrevHash.length())) +
                               " got prev=" + actualPrevHash.substring(0, Math.min(8, actualPrevHash.length())));
            return false;
        }

        // FIX #59: Re-simulate before committing to guard against stale-state
        long currentRound = block.getRound();
        if (!block.isEmpty()) {
            if (!simulateBlock(block, currentRound)) {
                System.err.println("[StateEngine] applyBlock REJECTED — re-simulation failed for round=" + currentRound);
                return false;
            }
        }

        // Derive proposer on-chain address for DONATE 2% fee routing (FIX #57)
        String proposerPubKey = block.getHeader().getProposerPubKey();
        String proposerAddr   = (proposerPubKey != null && !proposerPubKey.isEmpty()
                                  && !"GENESIS".equals(proposerPubKey))
                                ? Ed25519Util.deriveAddress(proposerPubKey)
                                : "";

        // FIX #63: Check saveBlock() result and propagate failure
        boolean success = db.saveBlock(block, proposerAddr);

        if (success) {
            System.out.println("[StateEngine] Block applied: " + block);
        } else {
            // FIX #63: Log failure
            System.err.println("[StateEngine] applyBlock: db.saveBlock() returned false for round=" + currentRound);
        }

        return success; // FIX #63: propagate failure correctly
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Converts a hex string to a byte array. */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte)((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    // -------------------------------------------------------------------------
    // Passthrough Accessors
    // -------------------------------------------------------------------------

    public DatabaseManager getDb()             { return db; }
    public String  getLatestBlockHash()        { return db.getLatestBlockHash(); }
    public String  getLatestSeed()             { return db.getLatestSeed(); }
    public long    getLatestRound()            { return db.getLatestRound(); }
    public boolean transactionExists(String id){ return db.transactionExists(id); }
}
