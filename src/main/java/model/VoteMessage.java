package model;

import com.google.gson.Gson;

/**
 * VoteMessage — Polymorphic consensus message.
 *
 * Integration Plan §1.2:
 *  - Represents SoftVote (Phase 3 Filter), CertifyVote (Phase 6), BBA_Gossip (Phase 5 Step A),
 *    and CommonCoin (Phase 5 Step C) depending on the Step field.
 *
 * Fixes applied:
 *  FIX #28 — Added vrfHash field (separate from vrfProof, which is the Ed25519 signature).
 *             vrfProof = Ed25519_Sign(privKey, vrfInput)  [64-byte signature, Base64]
 *             vrfHash  = SHA-512(vrfProof)                [128-char hex — the uniform random output]
 *             Both are needed for VRF verification. Previously only vrfProof was carried.
 *
 *  FIX #29 — Added coinHash field. For COMMON_COIN step, this holds the VRF coin output hash.
 *             The choice field no longer doubles as the coin hash, eliminating semantic overloading.
 *
 *  FIX #36/#37 — Added iteration field for BBA_GOSSIP and COMMON_COIN steps.
 *             BBA* runs multiple iterations per round. Without an iteration field, votes from
 *             iteration 1 pollute iteration 2's count window. Now keyed by (round, step, iteration).
 *
 *  FIX #46 — Added blockHashSignature field for CERTIFY_VOTE step.
 *             ed25519Signature = signs getSignableData() (message integrity).
 *             blockHashSignature = signs just the block hash (for inclusion in BlockCertificate).
 *             These are now separate fields with non-overlapping semantics.
 *
 *  FIX #49 — Safe substring in toString() using Math.min.
 */
public class VoteMessage {

    /** Sentinel value for the Choice field when voting for an empty block. */
    public static final String BOTTOM = "BOTTOM";

    // -------------------------------------------------------------------------
    // Step Enum — Identifies which phase this vote belongs to
    // -------------------------------------------------------------------------
    public enum Step {
        /**
         * PROPOSAL: Phase 1/2. Carries the proposer's block hash as a routing tag.
         * FIX #20: PROPOSAL messages now use their own step, not SOFT_VOTE,
         * preventing Phase 2 proposal metadata from polluting Phase 4 vote counts.
         */
        PROPOSAL,

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
         * FIX #36: Use the iteration field to separate BBA iterations.
         */
        BBA_GOSSIP,

        /**
         * COMMON_COIN: Phase 5 (BBA* Step C). Each node broadcasts its VRF hash
         * to allow the network to collectively derive the global Common Coin.
         * FIX #37: Use the iteration and coinHash fields; choice is not used.
         */
        COMMON_COIN
    }

    // -------------------------------------------------------------------------
    // Core Fields
    // -------------------------------------------------------------------------

    /** The consensus round this vote belongs to. */
    private long round;

    /** Which phase/step this vote represents. */
    private Step step;

    /**
     * Base64-encoded Ed25519 public key of the voter.
     * FIX #23: NetworkEngine verifies that this matches the envelope senderPubKey.
     */
    private String senderPubKey;

    /**
     * The binary vote: either a BlockHash hex string or the BOTTOM sentinel.
     * For COMMON_COIN step, this field is NOT used — use coinHash instead.
     * FIX #29: semantic overloading of choice for COMMON_COIN is removed.
     */
    private String choice;

    /**
     * Base64-encoded VRF proof (Ed25519 signature over the VRF input).
     * VRF_Proof = Ed25519_Sign(senderPrivateKey, VRF_Input)
     */
    private String vrfProof;

    /**
     * FIX #28: The VRF output hash (SHA-512 of vrfProof bytes), as a 128-char hex string.
     * This is the field used for sortition weight calculation and leader sorting.
     * Separate from vrfProof which is only the Ed25519 signature portion.
     */
    private String vrfHash;

    /**
     * Number of "seats" this node won in the sortition lottery.
     * Receivers MUST independently verify this weight via verifyVRFStake().
     * FIX #32/#33/#34: This declared weight is never trusted directly; always re-verified.
     */
    private int sortitionWeight;

    /**
     * Base64-encoded Ed25519 signature over getSignableData().
     * Used for network message integrity (covers all fields including vrfHash).
     * FIX #46: Does NOT cover just the block hash — that is blockHashSignature.
     */
    private String ed25519Signature;

    /**
     * FIX #46: CERTIFY_VOTE only. Ed25519 signature of just the blockHash (choice).
     * This signature goes into BlockCertificate.CertEntry.signature.
     * Separate from ed25519Signature which signs the full vote metadata.
     */
    private String blockHashSignature;

    /**
     * FIX #36/#37: BBA_GOSSIP and COMMON_COIN only.
     * Tracks which BBA* loop iteration this vote belongs to.
     * The ForwardCache is keyed by (round, step, iteration) to prevent
     * stale votes from iteration N polluting iteration N+1's count window.
     */
    private int iteration;

    /**
     * FIX #29: COMMON_COIN step only. The VRF coin hash (the coin output).
     * The choice field is left null for COMMON_COIN messages.
     * Phase 5's coin collection reads coinHash, not choice.
     */
    private String coinHash;

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    /**
     * Canonical signable data string.
     * FIX #28: Includes vrfHash so receivers can verify the declared hash is signed.
     * FIX #36: Includes iteration so BBA iteration voting is tamper-evident.
     * FIX #46: Does NOT include blockHashSignature (it is computed over just the choice).
     */
    public String getSignableData() {
        return round + "|" + step + "|" + senderPubKey + "|" +
               (choice != null ? choice : "") + "|" +
               (vrfProof != null ? vrfProof : "") + "|" +
               (vrfHash != null ? vrfHash : "") + "|" +
               sortitionWeight + "|" + iteration + "|" +
               (coinHash != null ? coinHash : "");
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

    /** FIX #28: VRF output hash (SHA-512 of proof), 128 hex chars. */
    public String getVrfHash()                          { return vrfHash; }
    public void   setVrfHash(String hash)               { this.vrfHash = hash; }

    public int    getSortitionWeight()                  { return sortitionWeight; }
    public void   setSortitionWeight(int w)             { this.sortitionWeight = w; }

    /** Signs getSignableData(). Used for network message integrity. */
    public String getEd25519Signature()                 { return ed25519Signature; }
    public void   setEd25519Signature(String sig)       { this.ed25519Signature = sig; }

    /** FIX #46: Signs just the block hash (choice). Used for certificate assembly. */
    public String getBlockHashSignature()               { return blockHashSignature; }
    public void   setBlockHashSignature(String sig)     { this.blockHashSignature = sig; }

    /** FIX #36/#37: BBA* iteration counter for BBA_GOSSIP and COMMON_COIN steps. */
    public int    getIteration()                        { return iteration; }
    public void   setIteration(int iteration)           { this.iteration = iteration; }

    /** FIX #29: Coin output hash for COMMON_COIN step only. */
    public String getCoinHash()                         { return coinHash; }
    public void   setCoinHash(String h)                 { this.coinHash = h; }

    @Override
    public String toString() {
        // FIX #49: Safe substring — choice may be "BOTTOM" (6 chars) not 8
        String choiceShort = (choice != null)
            ? choice.substring(0, Math.min(8, choice.length()))
            : "null";
        return "[Vote " + step + " | round=" + round + " | iter=" + iteration +
               " | choice=" + choiceShort + " | weight=" + sortitionWeight + "]";
    }
}
