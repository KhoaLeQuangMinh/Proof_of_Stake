package model;

import com.google.gson.Gson;

/**
 * BlockHeader — The cryptographic header of an Algorand-style block.
 *
 * Integration Plan §1.1:
 *  - Contains Seed: The chained Randomness Beacon used as VRF_Input for the
 *    next round's lottery. Prevents Grinding Attacks by locking the seed in
 *    stone before any party knows who the next proposer will be.
 *  - ProposerPubKey + Ed25519_Signature: Fast signature allows the P2P
 *    network to route and verify headers without doing heavy VRF math.
 *  - TransactionMerkleRoot: Commitment to the exact set of transactions in
 *    the block. Allows lightweight clients to verify inclusion proofs.
 */
public class BlockHeader {

    /** The block round number (monotonically increasing from genesis). */
    private long round;

    /** Unix epoch milliseconds when this block was proposed. */
    private long timestamp;

    /**
     * SHA-256 hash of the previous block's full header JSON.
     * Ensures the blockchain is cryptographically linked and immutable.
     * For the genesis block (round 0), this is a hardcoded zero-hash string.
     */
    private String previousBlockHash;

    /**
     * SHA-256 Merkle root of all transaction IDs in this block.
     * Computed as SHA-256(SHA-256(txId_1) + SHA-256(txId_2) + ... + SHA-256(txId_n)).
     * For empty blocks (Bottom), this is a zero-hash string.
     */
    private String transactionMerkleRoot;

    /**
     * VRF Proof: Base64-encoded Ed25519 signature used as the VRF proof.
     * VRF_Proof = Ed25519_Sign(proposerPrivateKey, VRF_Input)
     * where VRF_Input = previousBlock.Seed.
     * Any node can verify this using the proposer's public key.
     */
    private String proposerVRFProof;

    /**
     * The new Randomness Beacon seed for the NEXT round's VRF lottery.
     * Seed_N = SHA-256(VRF_Proof || round)
     * This ensures each round's lottery uses a seed that was unknowable
     * to any attacker before the proposer was selected.
     */
    private String seed;

    /**
     * Base64-encoded Ed25519 public key of the node that proposed this block.
     * Allows the P2P network to verify the header signature cheaply.
     */
    private String proposerPubKey;

    /**
     * Base64-encoded Ed25519 signature over the header's signable data.
     * Header_Sig = Ed25519_Sign(proposerPrivateKey, getSignableData())
     * Used for fast routing verification without VRF math.
     */
    private String ed25519Signature;

    /** SHA-256 hash of this header's JSON representation (self-referential). */
    private String hash;

    private static final Gson GSON = new Gson();

    /**
     * The canonical data string that the proposer signs.
     * Excludes ed25519Signature and hash to avoid circular dependency.
     */
    public String getSignableData() {
        return round + "|" + timestamp + "|" + previousBlockHash + "|" +
               transactionMerkleRoot + "|" + proposerVRFProof + "|" +
               seed + "|" + proposerPubKey;
    }

    public String toJson()                              { return GSON.toJson(this); }
    public static BlockHeader fromJson(String json)     { return GSON.fromJson(json, BlockHeader.class); }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public long   getRound()                            { return round; }
    public void   setRound(long round)                  { this.round = round; }

    public long   getTimestamp()                        { return timestamp; }
    public void   setTimestamp(long timestamp)          { this.timestamp = timestamp; }

    public String getPreviousBlockHash()                { return previousBlockHash; }
    public void   setPreviousBlockHash(String h)        { this.previousBlockHash = h; }

    public String getTransactionMerkleRoot()            { return transactionMerkleRoot; }
    public void   setTransactionMerkleRoot(String root) { this.transactionMerkleRoot = root; }

    public String getProposerVRFProof()                 { return proposerVRFProof; }
    public void   setProposerVRFProof(String proof)     { this.proposerVRFProof = proof; }

    public String getSeed()                             { return seed; }
    public void   setSeed(String seed)                  { this.seed = seed; }

    public String getProposerPubKey()                   { return proposerPubKey; }
    public void   setProposerPubKey(String key)         { this.proposerPubKey = key; }

    public String getEd25519Signature()                 { return ed25519Signature; }
    public void   setEd25519Signature(String sig)       { this.ed25519Signature = sig; }

    public String getHash()                             { return hash; }
    public void   setHash(String hash)                  { this.hash = hash; }
}
