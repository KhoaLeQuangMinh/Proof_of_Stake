package state;

import model.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PendingStateOverlay — In-memory simulation sandbox for block validation.
 *
 * Integration Plan §4.1 (State Engine State Variables):
 *  - An in-memory HashMap used to simulate transactions fast without touching
 *    the physical disk during simulateBlock() (Phase 2 Pre-Validation).
 *
 * How it Works:
 *  1. Before simulation: loadFromDB() pulls current balances and stakes from
 *     SQLite into this RAM overlay.
 *  2. During simulation: simulate() applies each transaction against the
 *     in-memory maps, checking for insufficient balance or invalid amounts.
 *  3. If simulation succeeds: the overlay is DISCARDED. SQLite is unchanged.
 *  4. If the block is later certified: applyBlock() in StateEngine writes to
 *     SQLite using ACID commits — the overlay played no role in that commit.
 *
 * This separation guarantees:
 *  - Zero disk I/O bottlenecks during high-volume pre-validation.
 *  - Zero state corruption if simulation fails midway.
 *  - A clean separation between "might be valid" and "is permanently committed".
 *
 * Thread Safety:
 *  Each simulateBlock() call creates a NEW PendingStateOverlay instance.
 *  The overlay is never shared between concurrent threads.
 */
public class PendingStateOverlay {

    // In-memory balance snapshots: pubKey -> balance
    private final Map<String, Double> balances = new HashMap<>();

    // In-memory stake snapshots: pubKey -> staked amount
    private final Map<String, Double> stakes   = new HashMap<>();

    // -------------------------------------------------------------------------
    // Population
    // -------------------------------------------------------------------------

    /**
     * Pre-loads balances and stakes for a set of addresses from the database.
     * Called once before simulation begins.
     *
     * @param db        The DatabaseManager to read current state from.
     * @param addresses All unique sender/receiver addresses in the proposed block.
     */
    public void loadFromDB(DatabaseManager db, List<String> addresses) {
        for (String address : addresses) {
            if (!balances.containsKey(address)) {
                balances.put(address, db.getBalance(address));
            }
            if (!stakes.containsKey(address)) {
                stakes.put(address, db.getStakeAmount(address));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Simulation
    // -------------------------------------------------------------------------

    /**
     * Simulates the effect of a single transaction on the in-memory overlay.
     * Does NOT touch SQLite. Does NOT fire EventBus hooks (those are fired by
     * the StateEngine that calls this).
     *
     * Validates:
     *  1. Amount must be strictly positive and finite (anti-exploit check).
     *  2. FirstValid <= currentRound <= LastValid (validity window check).
     *  3. Sufficient balance or stake for the operation.
     *
     * Applies (if valid):
     *  - STAKE:    balance -= amount,  stake += amount
     *  - UNSTAKE:  stake   -= amount,  balance += amount
     *  - WITHDRAW: balance -= amount
     *  - DEPOSIT:  balance of receiver += amount
     *  - DONATE:   balance -= amount (sender), balance of receiver += (amount - 2% fee)
     *
     * Note on DONATE 2% proposer fee:
     *  The proposer fee is tracked separately in the overlay but is only
     *  actually credited during applyBlock() in the StateEngine, because
     *  only at that point do we know the definitive proposer address.
     *
     * @param tx           The transaction to simulate.
     * @param currentRound The current blockchain round (for validity window check).
     * @return true if the transaction is valid and was applied to the overlay.
     */
    public boolean simulate(Transaction tx, long currentRound) {
        // ── Anti-exploit: amount must be strictly positive and finite ─────────
        if (tx.getAmount() <= 0 || Double.isNaN(tx.getAmount()) || Double.isInfinite(tx.getAmount())) {
            return false;
        }

        // ── Validity Window: FirstValid <= currentRound <= LastValid ──────────
        if (currentRound < tx.getFirstValid() || currentRound > tx.getLastValid()) {
            return false;
        }

        String sender   = tx.getSenderPubKey();
        String receiver = tx.getReceiverPubKey();
        double amount   = tx.getAmount();

        double senderBalance = balances.getOrDefault(sender, 0.0);
        double senderStake   = stakes.getOrDefault(sender, 0.0);

        switch (tx.getType()) {
            case STAKE -> {
                // Move funds from balance into stake
                if (senderBalance < amount) return false;
                balances.put(sender, senderBalance - amount);
                stakes.put(sender, senderStake + amount);
            }
            case UNSTAKE -> {
                // Return funds from stake to balance
                if (senderStake < amount) return false;
                stakes.put(sender, senderStake - amount);
                balances.put(sender, senderBalance + amount);
            }
            case WITHDRAW -> {
                // Funds leave the system entirely
                if (senderBalance < amount) return false;
                balances.put(sender, senderBalance - amount);
            }
            case DEPOSIT -> {
                // Funds enter the system — credit receiver only
                if (receiver == null || receiver.isEmpty()) return false;
                double receiverBalance = balances.getOrDefault(receiver, 0.0);
                balances.put(receiver, receiverBalance + amount);
            }
            case DONATE -> {
                // Transfer with 2% proposer fee
                if (senderBalance < amount) return false;
                if (receiver == null || receiver.isEmpty()) return false;

                // Use integer math to avoid floating point drift (same as TransactionValidator)
                long amountCents      = Math.round(amount * 100.0);
                long feeCents         = (amountCents * 2) / 100;
                long receiverCents    = amountCents - feeCents;

                balances.put(sender, senderBalance - amount);
                double receiverBalance = balances.getOrDefault(receiver, 0.0);
                balances.put(receiver, receiverBalance + (receiverCents / 100.0));
                // Note: feeCents will be credited to proposer during applyBlock(), not here
            }
            default -> { return false; }
        }

        return true; // All checks passed, overlay updated
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public double getBalance(String address) {
        return balances.getOrDefault(address, 0.0);
    }

    public double getStake(String address) {
        return stakes.getOrDefault(address, 0.0);
    }
}
