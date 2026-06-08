package model;

import com.google.gson.Gson;

/**
 * Transaction — Core on-chain state-altering command.
 *
 * Integration Plan §1.1:
 *  - Type Enum: STAKE, UNSTAKE, DEPOSIT, WITHDRAW, DONATE
 *  - Concurrency Control: FirstValid + LastValid block counters replacing nonce_order,
 *    allowing parallel transaction processing without sequential locking.
 *  - Cryptography: Ed25519 signature (BouncyCastle), replacing ECDSA.
 */
public class Transaction {

    // -------------------------------------------------------------------------
    // Transaction Type Enum — 5 domain-specific operation types
    // -------------------------------------------------------------------------
    public enum Type {
        /**
         * STAKE: Moves funds from sender wallet balance into their stake ledger.
         * Activates the node as a validator once stake > 0.
         */
        STAKE,
        /**
         * UNSTAKE: Returns funds from stake ledger back to wallet balance.
         * If resulting stake == 0, deactivates the node as a validator.
         */
        UNSTAKE,
        /**
         * DEPOSIT: System -> User. Credits the target's balance. Used for
         * external fiat-to-crypto on-ramp operations.
         */
        DEPOSIT,
        /**
         * WITHDRAW: User -> System. Debits the sender's balance. Funds leave
         * the on-chain system entirely.
         */
        WITHDRAW,
        /**
         * DONATE: User -> User. Transfers amount from sender to receiver.
         * A 2% proposer fee is deducted from the amount and credited to the
         * block proposer who includes this transaction. This is the only
         * transaction type that generates proposer rewards.
         */
        DONATE
    }

    // -------------------------------------------------------------------------
    // Core Fields
    // -------------------------------------------------------------------------

    /** Unique ID: SHA-256 of the signable data string. */
    private String txId;

    /** The operation type (STAKE, UNSTAKE, DEPOSIT, WITHDRAW, DONATE). */
    private Type type;

    /**
     * Base64-encoded Ed25519 public key of the sender.
     * Used for signature verification. The sender's on-chain address is
     * derived from SHA-256(senderPubKey)[0:40].
     */
    private String senderPubKey;

    /**
     * Base64-encoded Ed25519 public key of the receiver.
     * May be empty for STAKE/UNSTAKE/WITHDRAW operations.
     */
    private String receiverPubKey;

    /** Amount in the chain's native token units. Must be strictly positive. */
    private double amount;

    /**
     * Transaction fee paid to the network protocol sink account.
     * Per the architecture plan, fees do NOT go to the proposer directly.
     * Only the 2% DONATE fee is credited to the proposer.
     */
    private double fee;

    /**
     * FirstValid: The earliest block round at which this transaction is valid.
     * Replaces Ethereum-style nonce_order. Allows parallel processing:
     * multiple transactions from the same sender can be valid simultaneously
     * if their validity windows do not conflict.
     */
    private long firstValid;

    /**
     * LastValid: The latest block round at which this transaction can be included.
     * After this round, the transaction is permanently expired and must be purged
     * from any pending buffer. Prevents indefinite replay attacks.
     */
    private long lastValid;

    /** Unix epoch milliseconds when the transaction was originally created. */
    private long timestamp;

    /** Optional plaintext note or metadata attached to the transaction. */
    private String note;

    /**
     * Base64-encoded Ed25519 signature over getSignableData().
     * Computed by the sender using their private key.
     */
    private String ed25519Signature;

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    /**
     * Produces the deterministic string that the sender signs.
     * The signature field is intentionally excluded — you cannot sign the signature.
     * This exact string is also used to verify the signature by any receiver.
     */
    public String getSignableData() {
        return txId + "|" + type + "|" + senderPubKey + "|" +
               (receiverPubKey != null ? receiverPubKey : "") + "|" +
               String.format(java.util.Locale.US, "%.8f", amount) + "|" +
               String.format(java.util.Locale.US, "%.8f", fee) + "|" +
               firstValid + "|" + lastValid + "|" + timestamp + "|" +
               (note != null ? note : "");
    }

    public String toJson()                            { return GSON.toJson(this); }
    public static Transaction fromJson(String json)   { return GSON.fromJson(json, Transaction.class); }

    // -------------------------------------------------------------------------
    // Getters & Setters
    // -------------------------------------------------------------------------

    public String getTxId()                           { return txId; }
    public void   setTxId(String txId)                { this.txId = txId; }

    public Type   getType()                           { return type; }
    public void   setType(Type type)                  { this.type = type; }

    public String getSenderPubKey()                   { return senderPubKey; }
    public void   setSenderPubKey(String k)           { this.senderPubKey = k; }

    public String getReceiverPubKey()                 { return receiverPubKey; }
    public void   setReceiverPubKey(String k)         { this.receiverPubKey = k; }

    public double getAmount()                         { return amount; }
    public void   setAmount(double amount)            { this.amount = amount; }

    public double getFee()                            { return fee; }
    public void   setFee(double fee)                  { this.fee = fee; }

    public long   getFirstValid()                     { return firstValid; }
    public void   setFirstValid(long firstValid)      { this.firstValid = firstValid; }

    public long   getLastValid()                      { return lastValid; }
    public void   setLastValid(long lastValid)        { this.lastValid = lastValid; }

    public long   getTimestamp()                      { return timestamp; }
    public void   setTimestamp(long timestamp)        { this.timestamp = timestamp; }

    public String getNote()                           { return note; }
    public void   setNote(String note)                { this.note = note; }

    public String getEd25519Signature()               { return ed25519Signature; }
    public void   setEd25519Signature(String sig)     { this.ed25519Signature = sig; }

    @Override
    public String toString() {
        return "[Tx " + (txId != null ? txId.substring(0, 8) : "null") +
               " | " + type + " | " + amount + "]";
    }
}
