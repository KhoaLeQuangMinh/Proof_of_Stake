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
 *
 * Fixes applied:
 *  FIX #42: CertEntry now carries vrfHash (the VRF output hash, not the pubKey).
 *           verifyCertificate() was incorrectly passing voterPubKey as the vrfHash argument.
 *
 *  FIX #47/#48: BOTTOM sentinel unification.
 *           Certificates for Bottom rounds always use Block.BOTTOM_HASH (64 zeros),
 *           never the string literal "BOTTOM". isBottom() checks only one form.
 *
 *  FIX #49/#50: All substring calls use Math.min to prevent crashes on short strings.
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

        /** Base64-encoded VRF proof (Ed25519 signature over VRF input). */
        public String vrfProof;

        /**
         * FIX #42: VRF output hash (SHA-512 of vrfProof bytes), 128-char hex.
         * Previously this field was absent and voterPubKey was incorrectly used
         * in its place inside verifyCertificate(). That caused all cert verifications
         * to fail since a public key is not a valid VRF hash.
         */
        public String vrfHash;

        /** Number of sortition seats this voter won (their weight). */
        public int weight;

        /**
         * FIX #46: Base64-encoded Ed25519 signature of this voter over the block hash.
         * This is the blockHashSignature from VoteMessage, NOT the full vote signature.
         */
        public String signature;

        public CertEntry() {}
        public CertEntry(String voterPubKey, String vrfProof, String vrfHash, int weight, String signature) {
            this.voterPubKey = voterPubKey;
            this.vrfProof    = vrfProof;
            this.vrfHash     = vrfHash;
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
     * FIX #47: If this round concluded with Bottom, this MUST be Block.BOTTOM_HASH
     * (the 64-char zero hex string), NOT the string "BOTTOM".
     * Enforcement: assembleCertificate() converts "BOTTOM" choice to BOTTOM_HASH
     * before setting this field.
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
     * FIX #42: Now also accepts vrfHash so verifyCertificate() can use it correctly.
     *
     * @param voterPubKey  The voter's Ed25519 public key (Base64).
     * @param vrfProof     The voter's VRF proof (Base64).
     * @param vrfHash      The voter's VRF output hash (128-char hex). FIX #42.
     * @param weight       Sortition weight (seats) the voter won.
     * @param signature    The voter's Ed25519 blockHashSignature over the blockHash. FIX #46.
     */
    public void addVote(String voterPubKey, String vrfProof, String vrfHash, int weight, String signature) {
        votes.add(new CertEntry(voterPubKey, vrfProof, vrfHash, weight, signature));
        totalWeight += weight;
    }

    /**
     * Returns true if the certificate has enough weight to be considered final.
     * Threshold = >68% of expectedCommitteeSize.
     */
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

    /**
     * FIX #47/#48: True if this certificate was issued for a Bottom (empty) block.
     * Only checks against BOTTOM_HASH (the 64-char zero string).
     * The string "BOTTOM" should NEVER appear as a certificate blockHash —
     * the assembler is responsible for converting "BOTTOM" votes to BOTTOM_HASH.
     */
    public boolean isBottom() {
        // FIX #48: unified check — also handle legacy "BOTTOM" string defensively
        return BOTTOM_HASH.equals(blockHash) || VoteMessage.BOTTOM.equals(blockHash);
    }

    @Override
    public String toString() {
        // FIX #49/#50: safe substring for short strings like "BOTTOM"
        String hashShort = (blockHash != null)
            ? blockHash.substring(0, Math.min(8, blockHash.length()))
            : "null";
        return "[Certificate round=" + round + " | hash=" + hashShort +
               " | weight=" + totalWeight + " | votes=" + votes.size() + "]";
    }
}
