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
 *  - Manages the SQLite database and the VRF math.
 *  - Routes all EventBus hooks through the Singleton EventGateway.
 *  - Uses the PendingStateOverlay for disk-free block simulation.
 *
 * Critical Design Principle:
 *  simulateBlock() MUST NOT permanently mutate the SQLite database.
 *  applyBlock() is the ONLY method that commits to SQLite.
 *  This separation guarantees that:
 *    - Failed simulations produce zero side-effects.
 *    - Certified blocks are committed atomically via ACID transactions.
 */
public class StateEngine {

    /** R-320 lookback: how many blocks back to look for stake snapshots. */
    private static final int LOOKBACK_WINDOW = 320;

    /** Expected committee size for certify votes (for certificate verification). */
    private static final int EXPECTED_COMMITTEE = 1000;

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
     *
     * Integration Plan §4.2 getOnlineStake():
     *  Queries the ledger precisely 320 blocks in the past to prevent
     *  Flash Loan voting attacks. Returns total registered stake.
     *
     * @param currentRound The current consensus round.
     * @return Total stake (in token units) that was online 320 blocks ago.
     */
    public long getOnlineStake(long currentRound) {
        long lookbackRound = Math.max(0, currentRound - LOOKBACK_WINDOW);
        long stake = db.getOnlineStakeAtRound(lookbackRound);
        // Ensure at least 1 to avoid division-by-zero in Sortition
        return Math.max(1L, stake);
    }

    /**
     * Returns the stake of a specific address at the R-320 lookback round.
     *
     * @param pubKey       Base64-encoded Ed25519 public key of the address.
     * @param currentRound The current consensus round.
     * @return The staked amount this address had 320 blocks ago.
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
     * Integration Plan §4.2 verifyVRFStake():
     *  The heavy, CPU-intensive math check.
     *  1. Looks up the sender's balance at R-320 (Flash Loan defense).
     *  2. Executes the Binomial Distribution equation.
     *  3. Returns the number of "votes" the sender won.
     *  4. If 0, the message should be dropped.
     *
     * @param senderPubKey     Base64-encoded Ed25519 public key of the voter.
     * @param vrfProof         Base64-encoded VRF proof from the voter.
     * @param vrfHash          The VRF hash the voter claimed (hex string).
     * @param vrfInput         The VRF input used (previous block seed).
     * @param currentRound     The current round (used to calculate R-320).
     * @param expectedCommittee Expected committee size (20 for proposers, 1000 for voters).
     * @return Number of sortition seats this sender legitimately won (0 = not elected).
     */
    public int verifyVRFStake(String senderPubKey, String vrfProof, String vrfHash,
                               String vrfInput, long currentRound, int expectedCommittee) {
        try {
            // Step 1: Verify the VRF proof itself (Ed25519 + SHA-512)
            Ed25519PublicKeyParameters pubKey = Ed25519Util.decodePublicKey(senderPubKey);
            if (!VRF.verify(pubKey, vrfInput, vrfProof, vrfHash)) {
                return 0; // Invalid proof — drop
            }

            // Step 2: Look up stake at R-320 (Flash Loan defense)
            long myStake    = getAddressStake(senderPubKey, currentRound);
            long totalStake = getOnlineStake(currentRound);

            if (myStake <= 0) {
                return 0; // No stake — not eligible to participate
            }

            // Step 3: Run Binomial Distribution to determine seats won
            byte[] hashBytes = hexToBytes(vrfHash);
            int weight = Sortition.getSortitionWeight(hashBytes, myStake, totalStake, expectedCommittee);

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
     * Integration Plan §4.2 verifyCertificate():
     *  Iterates through all CertifyVote signatures inside the certificate,
     *  summing their verifyVRFStake weights against the R-320 snapshot.
     *  Returns true ONLY if the total weight strictly exceeds 2/3 of the
     *  Expected Committee (680 out of 1000).
     *
     * @param cert         The BlockCertificate to verify.
     * @param currentRound The round of this certificate.
     * @param vrfInput     The VRF input used by all voters (prev block seed).
     * @return true if the certificate represents genuine >2/3 supermajority.
     */
    public boolean verifyCertificate(BlockCertificate cert, long currentRound, String vrfInput) {
        if (cert == null || cert.getVotes() == null || cert.getVotes().isEmpty()) {
            return false;
        }

        int totalVerifiedWeight = 0;

        for (BlockCertificate.CertEntry entry : cert.getVotes()) {
            int weight = verifyVRFStake(
                entry.voterPubKey,
                entry.vrfProof,
                entry.voterPubKey, // In simplified VRF: hash was derived from proof
                vrfInput,
                currentRound,
                EXPECTED_COMMITTEE
            );
            // Also verify the Ed25519 signature over the block hash
            boolean sigValid = Ed25519Util.verifyFromBase64(
                entry.voterPubKey,
                cert.getBlockHash(),
                entry.signature
            );
            if (weight > 0 && sigValid) {
                totalVerifiedWeight += weight;
            }
        }

        // Must strictly exceed 68% of expected committee (680/1000)
        return totalVerifiedWeight > (int)(0.68 * EXPECTED_COMMITTEE);
    }

    // -------------------------------------------------------------------------
    // Block Simulation (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Simulates a proposed block in RAM without touching SQLite.
     *
     * Integration Plan §4.2 simulateBlock():
     *  - Creates the Pending State Overlay from SQLite.
     *  - Attempts to execute all transactions in RAM.
     *  - If a transaction fails, fires TXN_REJECTED(Invalid) via EventGateway.
     *  - Returns true if the block is mathematically valid.
     *  - The physical SQLite ledger MUST NOT be mutated.
     *
     * @param block        The proposed block to simulate.
     * @param currentRound The current consensus round (for validity window check).
     * @return true if all transactions are valid, false if any fail.
     */
    public boolean simulateBlock(Block block, long currentRound) {
        if (block == null || block.isEmpty()) {
            return true; // Empty blocks are always "valid" (they have no transactions)
        }

        List<Transaction> txs = block.getTransactions();
        if (txs == null || txs.isEmpty()) {
            return true;
        }

        // Collect all unique addresses that need balance lookups
        List<String> addresses = new ArrayList<>();
        for (Transaction tx : txs) {
            if (tx.getSenderPubKey() != null)   addresses.add(tx.getSenderPubKey());
            if (tx.getReceiverPubKey() != null) addresses.add(tx.getReceiverPubKey());
        }

        // Load balances into the Pending State Overlay (RAM only, no disk write)
        PendingStateOverlay overlay = new PendingStateOverlay();
        overlay.loadFromDB(db, addresses);

        boolean allValid = true;

        for (Transaction tx : txs) {
            // Check for duplicate txId (replay attack)
            if (db.transactionExists(tx.getTxId())) {
                System.out.println("[StateEngine] Simulation REJECT — duplicate txId: " + tx.getTxId());
                eventGateway.publishRejectedInvalid(tx, "DUPLICATE_TX_ID");
                allValid = false;
                continue;
            }

            // Verify Ed25519 signature
            boolean sigValid = Ed25519Util.verifyFromBase64(
                tx.getSenderPubKey(),
                tx.getSignableData(),
                tx.getEd25519Signature()
            );
            if (!sigValid) {
                System.out.println("[StateEngine] Simulation REJECT — invalid signature: " + tx.getTxId());
                eventGateway.publishRejectedInvalid(tx, "INVALID_SIGNATURE");
                allValid = false;
                continue;
            }

            // Simulate the transaction against the in-memory overlay
            if (!overlay.simulate(tx, currentRound)) {
                System.out.println("[StateEngine] Simulation REJECT — math/balance fail: " + tx.getTxId());
                // Phase 2 EventBus Hook: fire TXN_REJECTED through the Singleton EventGateway
                eventGateway.publishRejectedInvalid(tx, "INVALID_MATH_OR_BALANCE");
                allValid = false;
                // Continue checking remaining transactions (don't abort early)
            }
        }

        // The overlay is discarded here — SQLite remains completely unchanged
        return allValid;
    }

    // -------------------------------------------------------------------------
    // Block Application (§4.2)
    // -------------------------------------------------------------------------

    /**
     * Permanently commits a certified block to the SQLite ledger.
     *
     * Integration Plan §4.2 applyBlock():
     *  - Atomic Commit: Uses SQLite conn.setAutoCommit(false).
     *  - Custom Transaction Logic for STAKE, UNSTAKE, DEPOSIT, WITHDRAW.
     *  - DONATE Logic: 2% proposer fee credited to block proposer.
     *  - Phase 6 Commit EventBus Hook: fires TXN_VALIDATED for each committed tx.
     *  - Chain validation: previousBlockHash must match the ledger's current tip.
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
                               " expected prev=" + expectedPrevHash.substring(0, 8) +
                               " got prev=" + actualPrevHash.substring(0, 8));
            return false;
        }

        // Derive proposer on-chain address for DONATE 2% fee routing
        String proposerPubKey = block.getHeader().getProposerPubKey();
        String proposerAddr   = (proposerPubKey != null && !proposerPubKey.isEmpty())
                                ? Ed25519Util.deriveAddress(proposerPubKey)
                                : "";

        // Commit to SQLite using ACID transaction (inside saveBlock)
        boolean success = db.saveBlock(block, proposerAddr);

        if (success) {
            // Phase 6 Commit EventBus Hook: fire TXN_VALIDATED for each committed tx
            if (block.getTransactions() != null) {
                for (Transaction tx : block.getTransactions()) {
                    eventGateway.publishValidated(tx);
                }
            }
            System.out.println("[StateEngine] Block applied: " + block);
        }

        return success;
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
