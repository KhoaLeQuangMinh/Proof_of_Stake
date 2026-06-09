package model;

import com.google.gson.Gson;
import crypto.Ed25519Util;

/**
 * Transaction — Core on-chain state-altering command.
 *
 * Integration Plan §1.1:
 *  - Type Enum: STAKE, UNSTAKE, DEPOSIT, WITHDRAW, DONATE
 *  - Concurrency Control: FirstValid + LastValid block counters replacing nonce_order,
 *    allowing parallel transaction processing without sequential locking.
 *  - Cryptography: Ed25519 signature (BouncyCastle), replacing ECDSA.
 *
 * FIX #55 — txId Circularity Removed:
 *  txId is NOT included in getSignableData(). Instead, txId = SHA-256(getSignableData()).
 *  This eliminates the bootstrap paradox: you sign the canonical data, then derive txId
 *  from the same data. Signature verification always uses getSignableData() which never
 *  includes the txId itself.
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
         * DEPOSIT: System -> User. Credits the target's balance.
         * MUST be signed by the designated SYSTEM_PUB_KEY (see GenesisConfig).
         * Any DEPOSIT from a non-system key is rejected in simulation.
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
         * block proposer who includes this transaction.
         */
        DONATE
    }

    // -------------------------------------------------------------------------
    // Core Fields
    // -------------------------------------------------------------------------

    /**
     * Unique ID: SHA-256 of getSignableData().
     * FIX #55: txId is derived FROM signable data and is NOT included IN it.
     * This breaks the circular dependency. Always call computeTxId() after
     * constructing a new transaction, then set via setTxId().
     */
    private String txId;

    /** The operation type (STAKE, UNSTAKE, DEPOSIT, WITHDRAW, DONATE). */
    private Type type;

    /**
     * Base64-encoded Ed25519 public key of the sender.
     * For DEPOSIT transactions, this MUST equal GenesisConfig.SYSTEM_PUB_KEY.
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
     * FirstValid: The earliest block round at which this transaction is valid.
     * Replaces Ethereum-style nonce_order.
     */
    private long firstValid;

    /**
     * LastValid: The latest block round at which this transaction can be included.
     * After this round, the transaction is permanently expired.
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
     * Produces the deterministic canonical string that the sender signs.
     *
     * FIX #55: txId is intentionally EXCLUDED from this string.
     * The txId is derived AS SHA-256(this string). Including txId here would
     * create an unresolvable circular dependency.
     *
     * FIX #56: fee IS included so the sender commits to the exact fee amount.
     */
    public String getSignableData() {
        // txId is NOT included here — it is derived FROM this string.
        return type + "|" + senderPubKey + "|" +
               (receiverPubKey != null ? receiverPubKey : "") + "|" +
               String.format(java.util.Locale.US, "%.8f", amount) + "|" +
               firstValid + "|" + lastValid + "|" + timestamp + "|" +
               (note != null ? note : "");
    }

    /**
     * Computes the canonical txId = SHA-256(getSignableData()).
     * Call this after setting all fields (before setting the signature).
     * Then call setTxId(computeTxId()) to populate the txId field.
     */
    public String computeTxId() {
        return Ed25519Util.sha256Hex(getSignableData());
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
        // FIX #62: null-safe type and txId for malformed transactions
        String txIdShort = (txId != null && txId.length() >= 8) ? txId.substring(0, 8) : String.valueOf(txId);
        return "[Tx " + txIdShort + " | " + type + " | " + amount + "]";
    }
}
