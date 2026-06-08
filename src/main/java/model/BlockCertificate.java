package model;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * BlockCertificate — Mathematical proof that global consensus was reached.
 *
 * Integration Plan §1.2:
 *  - Round + BlockHash identify which block was agreed upon.
 *  - A collection of >2/3 committee member Ed25519 signatures (CertifyVotes)
 *    proves that the global network reached Byzantine agreement on this block.
 *  - Assembled by the NetworkEngine's certificate accumulator in the background
 *    during Phase 6 as CertifyVote messages arrive.
 *
 * Assembly Logic (NetworkEngine):
 *  When total sortitionWeight of received CertifyVotes for a single blockHash
 *  exceeds 68% of the expected committee size (1000), the certificate is
 *  assembled and the SystemInterrupt(CertificateEvent) is fired.
 */
public class BlockCertificate {

    /** Sentinel hash for rounds that concluded with an empty block (Bottom). */
    public static final String BOTTOM_HASH = Block.BOTTOM_HASH;

    // -------------------------------------------------------------------------
    // Certificate Entry — One voter's contribution to the certificate
    // -------------------------------------------------------------------------
    public static class CertEntry {
        /** Base64-encoded Ed25519 public key of the voter. */
        public String voterPubKey;

        /** Base64-encoded VRF proof of this voter's sortition win. */
        public String vrfProof;

        /** Number of sortition seats this voter won (their weight). */
        public int weight;

        /** Base64-encoded Ed25519 signature of this voter over the block hash. */
        public String signature;

        public CertEntry() {}
        public CertEntry(String voterPubKey, String vrfProof, int weight, String signature) {
            this.voterPubKey = voterPubKey;
            this.vrfProof    = vrfProof;
            this.weight      = weight;
            this.signature   = signature;
        }
    }

    // -------------------------------------------------------------------------
    // Core Fields
    // -------------------------------------------------------------------------

    /** The consensus round this certificate covers. */
    private long round;

    /**
     * The hash of the block that was agreed upon.
     * If this equals BOTTOM_HASH, the round concluded with an empty block.
     */
    private String blockHash;

    /** All certifying voter signatures collected to form this certificate. */
    private List<CertEntry> votes = new ArrayList<>();

    /** Running total of sortition weight from all collected CertifyVotes. */
    private int totalWeight;

    // -------------------------------------------------------------------------
    // Mutation Methods (called by NetworkEngine certificate assembler)
    // -------------------------------------------------------------------------

    /**
     * Adds a CertifyVote to the certificate.
     * Called by the NetworkEngine as valid CERTIFY_VOTE messages arrive.
     *
     * @param voterPubKey  The voter's Ed25519 public key.
     * @param vrfProof     The voter's VRF proof.
     * @param weight       Sortition weight (seats) the voter won.
     * @param signature    The voter's Ed25519 signature over the blockHash.
     */
    public void addVote(String voterPubKey, String vrfProof, int weight, String signature) {
        votes.add(new CertEntry(voterPubKey, vrfProof, weight, signature));
        totalWeight += weight;
    }

    /** Returns true if the certificate has enough weight to be considered final. */
    public boolean isFinal(int expectedCommitteeSize) {
        return totalWeight > (int)(0.68 * expectedCommitteeSize);
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    public String toJson()                                { return GSON.toJson(this); }
    public static BlockCertificate fromJson(String json)  { return GSON.fromJson(json, BlockCertificate.class); }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public long            getRound()                     { return round; }
    public void            setRound(long round)           { this.round = round; }

    public String          getBlockHash()                 { return blockHash; }
    public void            setBlockHash(String hash)      { this.blockHash = hash; }

    public List<CertEntry> getVotes()                     { return votes; }
    public void            setVotes(List<CertEntry> v)    { this.votes = v; }

    public int             getTotalWeight()               { return totalWeight; }
    public void            setTotalWeight(int w)          { this.totalWeight = w; }

    /** True if this certificate was issued for a Bottom (empty) block. */
    public boolean isBottom() { return BOTTOM_HASH.equals(blockHash); }

    @Override
    public String toString() {
        return "[Certificate round=" + round + " | hash=" +
               (blockHash != null ? blockHash.substring(0, 8) : "null") +
               " | weight=" + totalWeight + " | votes=" + votes.size() + "]";
    }
}
