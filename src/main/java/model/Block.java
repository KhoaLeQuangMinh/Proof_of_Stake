package model;

import com.google.gson.Gson;
import crypto.Ed25519Util;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Block — The fundamental unit of the blockchain ledger.
 *
 * Integration Plan §1.1:
 *  - Block = BlockHeader + []Transaction
 *  - Empty Block (Bottom): Created when the BBA* consensus concludes that
 *    no valid proposal was available (network partition or timeout). An empty
 *    block advances the round counter without committing any transactions.
 *  - The Block stores a BlockCertificate after consensus is finalized.
 *
 * Fixes applied:
 *  FIX #51: Empty blocks now get a unique, round-specific hash instead of the
 *           shared BOTTOM_HASH constant. This prevents UNIQUE constraint violations
 *           in the blocks table when multiple consecutive rounds produce empty blocks.
 *           Each empty block hash = SHA-256(previousHash + "|BOTTOM|" + round).
 *
 *  FIX #65: computeMerkleRoot() now builds a proper binary Merkle tree instead of
 *           the previous sequential rolling-hash approach. Leaf nodes = SHA-256(txId).
 *           Parent nodes = SHA-256(leftChild || rightChild). Odd-leaf rows duplicate
 *           the last leaf for balancing. This produces a true Merkle root that supports
 *           inclusion proofs.
 */
public class Block {

    /** Sentinel constant for a block with no transactions (the empty/Bottom marker). */
    public static final String BOTTOM_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private BlockHeader header;
    private List<Transaction> transactions;

    /**
     * Certificate attached after Phase 6 consensus. Not present during
     * proposal — only added when the block is finalized and saved to ledger.
     */
    private BlockCertificate certificate;

    // -------------------------------------------------------------------------
    // Factory Methods
    // -------------------------------------------------------------------------

    /**
     * Creates an empty Bottom block for rounds where consensus failed.
     *
     * FIX #51: The hash of the empty block is now round-specific:
     *   hash = SHA-256(previousHash + "|BOTTOM|" + round)
     * This ensures each empty block has a unique hash, preventing SQLite UNIQUE
     * constraint violations when multiple rounds produce empty blocks in sequence.
     * The BOTTOM_HASH constant (64 zeros) is only used as the previousBlockHash
     * for the very first block (genesis predecessor), never as a block's own hash.
     *
     * @param round         The current consensus round.
     * @param previousHash  Hash of the previous block.
     * @param timestamp     Current timestamp.
     * @param proposerPubKey The proposer's Ed25519 public key (empty for bottom blocks).
     * @return A fully-formed empty block with a unique round-specific hash.
     */
    public static Block createEmptyBlock(long round, String previousHash, long timestamp, String proposerPubKey) {
        Block b = new Block();
        BlockHeader h = new BlockHeader();
        h.setRound(round);
        h.setTimestamp(timestamp);
        h.setPreviousBlockHash(previousHash);
        h.setTransactionMerkleRoot(BOTTOM_HASH);
        h.setProposerVRFProof("");
        // Seed for next round derived from prev hash + round (deterministic, unique)
        h.setSeed(Ed25519Util.sha256Hex(previousHash + "|SEED|" + round));
        h.setProposerPubKey(proposerPubKey != null ? proposerPubKey : "");
        h.setEd25519Signature("");

        // FIX #51: Unique hash per empty block, keyed on round + previousHash
        String uniqueEmptyHash = Ed25519Util.sha256Hex(previousHash + "|BOTTOM|" + round);
        h.setHash(uniqueEmptyHash);

        b.setHeader(h);
        b.setTransactions(new ArrayList<>());
        return b;
    }

    /**
     * Returns true if this is an empty Bottom block with no transactions.
     * FIX #51: We now check transactions == empty AND merkleRoot == BOTTOM_HASH,
     * NOT the block hash (which is now unique per round).
     */
    public boolean isEmpty() {
        return (transactions == null || transactions.isEmpty()) &&
               header != null &&
               BOTTOM_HASH.equals(header.getTransactionMerkleRoot());
    }

    /**
     * Computes the SHA-256 binary Merkle root of all transaction IDs in this block.
     *
     * FIX #65: This is now a proper binary Merkle tree:
     *   Leaf[i] = SHA-256(txId_i)
     *   Parent  = SHA-256(left_child_bytes || right_child_bytes)
     *   If odd number of leaves, the last leaf is duplicated for balancing.
     *   The Merkle root is the single hash remaining after all pairings.
     *
     * For empty blocks, returns BOTTOM_HASH (the 64-char zero string).
     */
    public String computeMerkleRoot() {
        if (transactions == null || transactions.isEmpty()) {
            return BOTTOM_HASH;
        }
        try {
            // Step 1: Build leaf layer — SHA-256 of each txId
            List<byte[]> hashes = new ArrayList<>();
            for (Transaction tx : transactions) {
                hashes.add(sha256Bytes(tx.getTxId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }

            // Step 2: Iteratively pair and hash until one root remains
            while (hashes.size() > 1) {
                List<byte[]> next = new ArrayList<>();
                for (int i = 0; i < hashes.size(); i += 2) {
                    byte[] left  = hashes.get(i);
                    // Duplicate last hash if odd number of nodes
                    byte[] right = (i + 1 < hashes.size()) ? hashes.get(i + 1) : left;
                    next.add(sha256Bytes(concat(left, right)));
                }
                hashes = next;
            }

            // Convert root bytes to hex
            return bytesToHex(hashes.get(0));
        } catch (Exception e) {
            return BOTTOM_HASH;
        }
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    public String toJson()                      { return GSON.toJson(this); }
    public static Block fromJson(String json)   { return GSON.fromJson(json, Block.class); }

    // -------------------------------------------------------------------------
    // Binary Merkle Tree Helpers
    // -------------------------------------------------------------------------

    private static byte[] sha256Bytes(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) { return new byte[32]; }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public BlockHeader        getHeader()                         { return header; }
    public void               setHeader(BlockHeader header)       { this.header = header; }

    public List<Transaction>  getTransactions()                   { return transactions; }
    public void               setTransactions(List<Transaction> t){ this.transactions = t; }

    public BlockCertificate   getCertificate()                    { return certificate; }
    public void               setCertificate(BlockCertificate c)  { this.certificate = c; }

    /** Convenience accessor for the block's round number. */
    public long getRound() { return header != null ? header.getRound() : -1; }

    /** Convenience accessor for the block's hash. */
    public String getHash() { return header != null ? header.getHash() : BOTTOM_HASH; }

    @Override
    public String toString() {
        int txCount = transactions != null ? transactions.size() : 0;
        String hashStr = getHash();
        // FIX #50: safe substring
        String hashShort = (hashStr != null) ? hashStr.substring(0, Math.min(8, hashStr.length())) : "null";
        return "[Block #" + getRound() + " | txs=" + txCount + " | hash=" + hashShort + "]";
    }
}
