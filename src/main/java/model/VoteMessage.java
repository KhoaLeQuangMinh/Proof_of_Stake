package model;

import com.google.gson.Gson;

/**
 * VoteMessage — Polymorphic consensus message.
 *
 * Integration Plan §1.2:
 *  - Represents SoftVote (Phase 3 Filter), CertifyVote (Phase 6), BBA_Gossip (Phase 5 Step A),
 *    and CommonCoin (Phase 5 Step C) depending on the Step field.
 *  - Choice: Binary — either a BlockHash string or the sentinel "BOTTOM".
 *  - VRFProof: Cryptographic proof of winning the sortition lottery.
 *  - Ed25519_Signature: Fast network identity verification.
 *  - SortitionWeight: Number of "seats" won in the lottery, verified against
 *    the sender's stake using the Binomial Distribution.
 */
public class VoteMessage {

    /** Sentinel value for the Choice field when voting for an empty block. */
    public static final String BOTTOM = "BOTTOM";

    // -------------------------------------------------------------------------
    // Step Enum — Identifies which phase this vote belongs to
    // -------------------------------------------------------------------------
    public enum Step {
        /**
         * SOFT_VOTE: Phase 3 (Filter). The node votes for the Best_Proposal
         * after Phase 2 pre-validation. Expected committee: ~1000.
         */
        SOFT_VOTE,

        /**
         * CERTIFY_VOTE: Phase 6 (Halting Condition). The node certifies the
         * Final_Winner determined by BBA*. Collected by the NetworkEngine
         * certificate assembler.
         */
        CERTIFY_VOTE,

        /**
         * BBA_GOSSIP: Phase 5 (BBA* Step A). The node broadcasts its current
         * binary choice (Hash X or BOTTOM) during the BBA* micro-loop.
         */
        BBA_GOSSIP,

        /**
         * COMMON_COIN: Phase 5 (BBA* Step C). Each node broadcasts its VRF hash
         * to allow the network to collectively derive the global Common Coin.
         * The node with the globally lowest VRF hash becomes the coin authority.
         */
        COMMON_COIN
    }

    // -------------------------------------------------------------------------
    // Core Fields
    // -------------------------------------------------------------------------

    /** The consensus round this vote belongs to. */
    private long round;

    /** Which phase/step this vote represents (SOFT_VOTE, CERTIFY_VOTE, etc.). */
    private Step step;

    /**
     * Base64-encoded Ed25519 public key of the voter.
     * Uniquely identifies the sender for equivocation detection and
     * one-vote-per-sender enforcement.
     */
    private String senderPubKey;

    /**
     * The binary vote: either a BlockHash hex string or the BOTTOM sentinel.
     * For COMMON_COIN step, this is the raw VRF hash output (hex string).
     */
    private String choice;

    /**
     * Base64-encoded VRF proof (Ed25519 signature over the VRF input).
     * VRF_Proof = Ed25519_Sign(senderPrivateKey, VRF_Input)
     * Allows any node to independently verify the sender's sortition result.
     */
    private String vrfProof;

    /**
     * Number of "seats" this node won in the sortition lottery.
     * Determined by the Binomial Distribution B(k; stake, p).
     * Receivers must independently verify this weight matches the VRF math.
     */
    private int sortitionWeight;

    /**
     * Base64-encoded Ed25519 signature over getSignableData().
     * Allows fast identity verification by the NetworkEngine without VRF math.
     */
    private String ed25519Signature;

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    /**
     * Canonical signable data string. Excludes the signature itself.
     */
    public String getSignableData() {
        return round + "|" + step + "|" + senderPubKey + "|" + choice + "|" +
               vrfProof + "|" + sortitionWeight;
    }

    public String toJson()                          { return GSON.toJson(this); }
    public static VoteMessage fromJson(String json) { return GSON.fromJson(json, VoteMessage.class); }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public long   getRound()                            { return round; }
    public void   setRound(long round)                  { this.round = round; }

    public Step   getStep()                             { return step; }
    public void   setStep(Step step)                    { this.step = step; }

    public String getSenderPubKey()                     { return senderPubKey; }
    public void   setSenderPubKey(String key)           { this.senderPubKey = key; }

    public String getChoice()                           { return choice; }
    public void   setChoice(String choice)              { this.choice = choice; }

    public String getVrfProof()                         { return vrfProof; }
    public void   setVrfProof(String proof)             { this.vrfProof = proof; }

    public int    getSortitionWeight()                  { return sortitionWeight; }
    public void   setSortitionWeight(int w)             { this.sortitionWeight = w; }

    public String getEd25519Signature()                 { return ed25519Signature; }
    public void   setEd25519Signature(String sig)       { this.ed25519Signature = sig; }

    @Override
    public String toString() {
        return "[Vote " + step + " | round=" + round + " | choice=" +
               (choice != null ? choice.substring(0, Math.min(8, choice.length())) : "null") +
               " | weight=" + sortitionWeight + "]";
    }
}
