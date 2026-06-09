package model;

import com.google.gson.Gson;

/**
 * NetworkMessage — Wire protocol envelope for all P2P communication.
 *
 * Integration Plan §2.2 (Network Engine):
 *  - All messages sent over TCP are wrapped in this envelope.
 *  - The NetworkEngine's ingressLoop() unpacks this envelope and routes
 *    the payload to the correct handler based on type.
 *  - The Ed25519 signature covers (type + round + payload) ensuring
 *    the NetworkEngine can cheaply verify sender identity before routing.
 */
public class NetworkMessage {

    // -------------------------------------------------------------------------
    // Message Type Enum
    // -------------------------------------------------------------------------
    public enum Type {
        /**
         * PROPOSAL: A block proposal from a sortition-selected proposer.
         * Payload: Block JSON. Triggers Proposer Equivocation Detection.
         */
        PROPOSAL,

        /**
         * SOFT_VOTE: Phase 3 vote for the best proposal.
         * Payload: VoteMessage JSON (Step=SOFT_VOTE).
         */
        SOFT_VOTE,

        /**
         * CERTIFY_VOTE: Phase 6 vote certifying the Final_Winner.
         * Payload: VoteMessage JSON (Step=CERTIFY_VOTE).
         * Triggers the NetworkEngine's certificate assembler.
         */
        CERTIFY_VOTE,

        /**
         * BBA_GOSSIP: Phase 5 BBA* Step A broadcast.
         * Payload: VoteMessage JSON (Step=BBA_GOSSIP).
         */
        BBA_GOSSIP,

        /**
         * COMMON_COIN: Phase 5 BBA* Step C coin broadcast.
         * Payload: VoteMessage JSON (Step=COMMON_COIN, Choice=VRF hash).
         */
        COMMON_COIN,

        /**
         * BLOCK_CERTIFICATE: Fully assembled BlockCertificate.
         * Triggers the SystemInterrupt(CertificateEvent) in the ConsensusEngine.
         */
        BLOCK_CERTIFICATE,

        /**
         * SYNC_REQUEST: Peer requesting blocks starting from a given round.
         * Payload: startRound as string.
         */
        SYNC_REQUEST,

        /**
         * SYNC_RESPONSE: Response to SYNC_REQUEST with a batch of blocks.
         * Payload: JSON array of Block objects.
         */
        SYNC_RESPONSE,

        /**
         * HANDSHAKE: Initial connection establishment.
         */
        HANDSHAKE
    }

    // -------------------------------------------------------------------------
    // Envelope Fields
    // -------------------------------------------------------------------------

    /** Message type — used by ingressLoop for routing. */
    private Type type;

    /**
     * The consensus round this message belongs to.
     * Used by the 10-block rule: if round > Network_Tip + 10, DROP.
     */
    private long round;

    /**
     * Base64-encoded Ed25519 public key of the sender node.
     * Used for cheap identity verification and equivocation detection.
     */
    private String senderPubKey;

    /**
     * JSON-serialized payload (Block, VoteMessage, BlockCertificate, etc.).
     * The NetworkEngine routes this to the correct handler without parsing
     * the full content — only the envelope fields are inspected for routing.
     */
    private String payload;

    /**
     * Base64-encoded Ed25519 signature over (type + round + payload).
     * Verified by the NetworkEngine's ingressLoop Step 2 (Cryptography check)
     * to reject forged/corrupted messages cheaply before deeper processing.
     */
    private String signature;

    /**
     * FIX #K: Handshake includes the ledgerTipHash for silent fork detection.
     */
    private String ledgerTipHash;

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    /**
     * Canonical data string that the sender signs.
     * Covers type, round, and payload — excluding the signature field.
     */
    public String getSignableData() {
        return type.name() + "|" + round + "|" + (payload != null ? payload : "") + "|" + (ledgerTipHash != null ? ledgerTipHash : "");
    }

    public String toJson()                              { return GSON.toJson(this); }
    public static NetworkMessage fromJson(String json)  { return GSON.fromJson(json, NetworkMessage.class); }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public Type   getType()                             { return type; }
    public void   setType(Type type)                    { this.type = type; }

    public long   getRound()                            { return round; }
    public void   setRound(long round)                  { this.round = round; }

    public String getSenderPubKey()                     { return senderPubKey; }
    public void   setSenderPubKey(String key)           { this.senderPubKey = key; }

    public String getPayload()                          { return payload; }
    public void   setPayload(String payload)            { this.payload = payload; }

    public String getSignature()                        { return signature; }
    public void   setSignature(String sig)              { this.signature = sig; }

    public String getLedgerTipHash()                    { return ledgerTipHash; }
    public void   setLedgerTipHash(String hash)         { this.ledgerTipHash = hash; }

    @Override
    public String toString() {
        return "[NetMsg " + type + " | round=" + round + " | sender=" +
               (senderPubKey != null ? senderPubKey.substring(0, Math.min(8, senderPubKey.length())) : "null") + "]";
    }
}
