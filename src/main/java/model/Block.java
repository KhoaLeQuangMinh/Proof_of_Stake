package model;

import com.google.gson.Gson;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
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
 */
public class Block {

    /** Sentinel constant used to represent a Bottom (empty) block hash. */
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
     * Per Phase 6 of the integrated plan: if Certificate.BlockHash == Bottom,
     * create an Empty Block and write it to the Ledger.
     *
     * @param round         The current consensus round.
     * @param previousHash  Hash of the previous block.
     * @param timestamp     Current timestamp.
     * @param proposerPubKey The proposer's Ed25519 public key (empty for bottom blocks).
     * @return A fully-formed empty block.
     */
    public static Block createEmptyBlock(long round, String previousHash, long timestamp, String proposerPubKey) {
        Block b = new Block();
        BlockHeader h = new BlockHeader();
        h.setRound(round);
        h.setTimestamp(timestamp);
        h.setPreviousBlockHash(previousHash);
        h.setTransactionMerkleRoot(BOTTOM_HASH);
        h.setProposerVRFProof("");
        // Seed for next round is derived from previous hash + round even for empty blocks
        h.setSeed(sha256Hex((previousHash + round).getBytes()));
        h.setProposerPubKey(proposerPubKey != null ? proposerPubKey : "");
        h.setEd25519Signature("");
        h.setHash(BOTTOM_HASH);
        b.setHeader(h);
        b.setTransactions(new ArrayList<>());
        return b;
    }

    /** Returns true if this is an empty Bottom block with no transactions. */
    public boolean isEmpty() {
        return (transactions == null || transactions.isEmpty()) &&
               BOTTOM_HASH.equals(header != null ? header.getHash() : null);
    }

    /**
     * Computes the SHA-256 Merkle root of all transaction IDs in this block.
     * For an empty block, returns the BOTTOM_HASH sentinel.
     * This is used to populate BlockHeader.transactionMerkleRoot.
     */
    public String computeMerkleRoot() {
        if (transactions == null || transactions.isEmpty()) {
            return BOTTOM_HASH;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Transaction tx : transactions) {
                digest.update(sha256Bytes(tx.getTxId().getBytes()));
            }
            byte[] root = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : root) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
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
    // Helpers
    // -------------------------------------------------------------------------

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] hash = d.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return BOTTOM_HASH; }
    }

    private static byte[] sha256Bytes(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) { return new byte[32]; }
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
        return "[Block #" + getRound() + " | txs=" + txCount + " | hash=" +
               (getHash() != null ? getHash().substring(0, 8) : "null") + "]";
    }
}
