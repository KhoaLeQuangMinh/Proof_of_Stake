package buffer;

import model.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventGateway — Singleton deduplication gateway for all upstream webhooks.
 *
 * Integration Plan §3.2 (The Singleton Event Gateway):
 *  A dedicated deduplication class that acts as the single source of truth
 *  for upstream webhooks. It prevents race conditions where a transaction
 *  could receive multiple conflicting event notifications.
 *
 * Problem it solves:
 *  Without this gateway, a transaction could be fired twice:
 *   1. TXN_REJECTED(Invalid) — from Phase 2 sandbox simulation failure.
 *   2. TXN_REJECTED(Network Timeout) — from Phase 6 Bottom block flush.
 *  This would send duplicate/contradictory events to the upstream system.
 *
 * Deduplication Cache:
 *  Maintains Map<TxId, Boolean> resolvedTransactions for the current round.
 *  - A transaction is "resolved" the moment any event is fired for it.
 *  - Subsequent attempts to fire events for the same TxId are silently swallowed.
 *  - resetForNewRound() clears the cache at the start of each round.
 *
 * Thread Safety:
 *  All public methods are synchronized. The EventGateway can be called
 *  from both the ConsensusEngine thread and the NetworkEngine thread pool.
 *
 * Extension Point:
 *  The publish* methods currently log to stdout. In production, replace
 *  these with actual EventBus.publish() calls (e.g., RabbitMQ, Kafka, etc.)
 */
public class EventGateway {

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final EventGateway INSTANCE = new EventGateway();

    private EventGateway() {}

    /** Returns the singleton EventGateway instance. */
    public static EventGateway getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * Round-scoped deduplication cache.
     * TxId -> true (the value is irrelevant; we only care about key presence).
     */
    private final ConcurrentHashMap<String, Boolean> resolvedTransactions = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Phase 6 (Commit) Hook — TXN_VALIDATED
    // -------------------------------------------------------------------------

    /**
     * Fires TXN_VALIDATED for a transaction that was successfully committed to SQLite.
     *
     * Integration Plan §3.2 Phase 6 (Commit) Hook:
     *  If a block is committed, the State Engine calls the Gateway.
     *  The Gateway fires TXN_VALIDATED and marks the TxId as resolved.
     *
     * @param tx The transaction that was successfully committed.
     */
    public synchronized void publishValidated(Transaction tx) {
        if (tx == null || tx.getTxId() == null) return;

        if (resolvedTransactions.putIfAbsent(tx.getTxId(), Boolean.TRUE) != null) {
            // Already resolved — silently swallow to prevent duplicate events
            System.out.println("[EventGateway] Swallowed duplicate VALIDATED for txId=" +
                               tx.getTxId().substring(0, 8));
            return;
        }

        // Fire the upstream webhook
        System.out.println("[EventGateway] ✅ TXN_VALIDATED | txId=" + tx.getTxId() +
                           " | type=" + tx.getType() + " | amount=" + tx.getAmount());

        // ── EXTENSION POINT ──────────────────────────────────────────────────
        // In production: eventBus.publish("TXN_VALIDATED", tx.toJson());
        // ────────────────────────────────────────────────────────────────────
    }

    // -------------------------------------------------------------------------
    // Phase 2 (Sandbox) Hook — TXN_REJECTED (Invalid)
    // -------------------------------------------------------------------------

    /**
     * Fires TXN_REJECTED for a transaction that failed Phase 2 simulation.
     *
     * Integration Plan §3.2 Phase 2 (Sandbox) Hook:
     *  If a transaction fails validation, the State Engine calls the Gateway.
     *  The Gateway checks the cache, fires TXN_REJECTED (Invalid), and marks
     *  the TxId as resolved so Phase 6 cannot fire a duplicate.
     *
     * @param tx     The transaction that failed validation.
     * @param reason A human-readable rejection reason code
     *               (e.g., "INVALID_MATH_OR_BALANCE", "INVALID_SIGNATURE").
     */
    public synchronized void publishRejectedInvalid(Transaction tx, String reason) {
        if (tx == null || tx.getTxId() == null) return;

        if (resolvedTransactions.putIfAbsent(tx.getTxId(), Boolean.TRUE) != null) {
            // Already resolved — silently swallow
            System.out.println("[EventGateway] Swallowed duplicate REJECTED(Invalid) for txId=" +
                               tx.getTxId().substring(0, 8));
            return;
        }

        // Fire the upstream webhook
        System.out.println("[EventGateway] ❌ TXN_REJECTED(Invalid) | txId=" + tx.getTxId() +
                           " | reason=" + reason + " | type=" + tx.getType());

        // ── EXTENSION POINT ──────────────────────────────────────────────────
        // In production: eventBus.publish("TXN_REJECTED", Map.of("txId", tx.getTxId(), "reason", reason));
        // ────────────────────────────────────────────────────────────────────
    }

    // -------------------------------------------------------------------------
    // Phase 6 (Timeout) Hook — TXN_REJECTED (Network Timeout)
    // -------------------------------------------------------------------------

    /**
     * Fires TXN_REJECTED(Network Timeout) for transactions in a failed Bottom round.
     *
     * Integration Plan §3.2 Phase 6 (Timeout) Hook:
     *  If the round ends in Bottom, the Consensus Engine peeks at the Buffer's
     *  10 transactions and calls the Gateway.
     *  The Gateway fires TXN_REJECTED (Network Timeout) ONLY for transactions
     *  that were NOT already marked as resolved in Phase 2.
     *
     * This is the critical deduplication point: transactions that were already
     * rejected in Phase 2 will NOT receive a second rejection event here.
     *
     * @param txs The full batch of transactions that were in the Shared Buffer
     *            during this failed round. May contain nulls if buffer was empty.
     */
    public synchronized void publishRejectedTimeout(List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) return;

        int fired    = 0;
        int swallowed = 0;

        for (Transaction tx : txs) {
            if (tx == null || tx.getTxId() == null) continue;

            if (resolvedTransactions.putIfAbsent(tx.getTxId(), Boolean.TRUE) != null) {
                // Already resolved (was rejected in Phase 2) — do NOT fire again
                swallowed++;
                continue;
            }

            // Fire the upstream webhook for this transaction
            System.out.println("[EventGateway] ⏱ TXN_REJECTED(Timeout) | txId=" + tx.getTxId() +
                               " | type=" + tx.getType());

            // ── EXTENSION POINT ──────────────────────────────────────────────
            // In production: eventBus.publish("TXN_REJECTED", Map.of("txId", tx.getTxId(), "reason", "NETWORK_TIMEOUT"));
            // ────────────────────────────────────────────────────────────────
            fired++;
        }

        System.out.println("[EventGateway] Timeout batch: fired=" + fired + " | deduplicated=" + swallowed);
    }

    // -------------------------------------------------------------------------
    // Round Reset
    // -------------------------------------------------------------------------

    /**
     * Clears the deduplication cache for the new round.
     *
     * Called by the ConsensusEngine at the very start of each new round,
     * after Current_Round++ and before Phase 1 begins.
     * Ensures that the cache from the previous round does not bleed into
     * the next round's transaction event tracking.
     */
    public synchronized void resetForNewRound() {
        resolvedTransactions.clear();
    }

    /** Returns the number of resolved transactions in the current round (for debugging). */
    public int getResolvedCount() {
        return resolvedTransactions.size();
    }
}
