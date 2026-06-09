package state;

import model.Transaction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PendingStateOverlay — In-memory simulation sandbox for block validation.
 *
 * Integration Plan §4.1 (State Engine State Variables):
 *  - An in-memory HashMap used to simulate transactions fast without touching
 *    the physical disk during simulateBlock() (Phase 2 Pre-Validation).
 *
 * Fixes applied:
 *
 *  FIX #5/#6 — DEPOSIT authority check:
 *   DEPOSIT transactions are only accepted if senderPubKey == GenesisConfig.SYSTEM_PUB_KEY.
 *   Any DEPOSIT from a non-system key is rejected immediately as "INVALID_DEPOSIT_AUTHORITY".
 *   This prevents any user from creating balance out of thin air.
 *
 *  FIX #54 — Intra-block duplicate txId detection:
 *   The overlay tracks txIds it has seen within the current block simulation.
 *   If the same txId appears twice in a single block, the second is rejected.
 *   This is separate from the DB replay-attack check (which covers cross-block dups).
 *
 *  FIX #56 — Fee Removal & 2% Reward:
 *   Transaction fees are removed. Instead, a DONATE transaction transfers 98%
 *   to the receiver and 2% to the block's proposer.
 *
 *  FIX #62 — Null type guard:
 *   If tx.getType() is null (malformed transaction), return false immediately
 *   instead of a NullPointerException in the switch statement.
 */
public class PendingStateOverlay {

    // In-memory balance snapshots: pubKey -> balance
    private final Map<String, Double> balances = new HashMap<>();

    // In-memory stake snapshots: pubKey -> staked amount
    private final Map<String, Double> stakes   = new HashMap<>();

    // FIX #54: Track txIds seen within this block to detect intra-block duplicates
    private final Set<String> seenTxIds = new HashSet<>();

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
            if (address == null || address.isEmpty()) continue;
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
     * Does NOT touch SQLite. Does NOT fire EventBus hooks.
     *
     * Validates:
     *  1. Amount must be strictly positive and finite (anti-exploit check).
     *  2. FIX #62: Type must not be null.
     *  3. FirstValid <= currentRound <= LastValid (validity window check).
     *  4. FIX #54: txId must not already appear in this block.
     *  5. FIX #5/#6: DEPOSIT sender must be SYSTEM_PUB_KEY.
     *  6. FIX #56: Combined amount + fee must be affordable by sender.
     *
     * @param tx             The transaction to simulate.
     * @param currentRound   The current blockchain round (for validity window check).
     * @param proposerPubKey The public key of the block proposer (receives 2% of DONATE).
     * @return true if the transaction is valid and was applied to the overlay.
     */
    public boolean simulate(Transaction tx, long currentRound, String proposerPubKey) {
        // FIX #62: Guard against null type (malformed transaction)
        if (tx.getType() == null) {
            return false;
        }

        // Anti-exploit: amount must be strictly positive and finite
        if (tx.getAmount() <= 0 || Double.isNaN(tx.getAmount()) || Double.isInfinite(tx.getAmount())) {
            return false;
        }

        // Validity Window: FirstValid <= currentRound <= LastValid
        if (currentRound < tx.getFirstValid() || currentRound > tx.getLastValid()) {
            return false;
        }

        // FIX #54: Intra-block duplicate detection
        String txId = tx.getTxId();
        if (txId == null || seenTxIds.contains(txId)) {
            return false; // Duplicate within this block
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
                // WITHDRAW must target the system key only
                if (!app.GenesisConfig.SYSTEM_PUB_KEY.equals(receiver)) {
                    return false; // Unauthorized WITHDRAW attempt
                }
                if (senderBalance < amount) return false;
                balances.put(sender, senderBalance - amount);
            }
            case DEPOSIT -> {
                // DEPOSIT must come from the system key only
                if (!app.GenesisConfig.SYSTEM_PUB_KEY.equals(sender)) {
                    return false; // Unauthorized DEPOSIT attempt
                }
                if (receiver == null || receiver.isEmpty()) return false;
                
                double receiverBalance = balances.getOrDefault(receiver, 0.0);
                balances.put(receiver, receiverBalance + amount);
            }
            case DONATE -> {
                // Transfer with 2% proposer fee
                if (senderBalance < amount) return false;
                if (receiver == null || receiver.isEmpty()) return false;
                if (proposerPubKey == null || proposerPubKey.isEmpty()) return false;

                long amountCents   = Math.round(amount * 100.0);
                long feeCents      = (amountCents * 2) / 100;
                long receiverCents = amountCents - feeCents;

                balances.put(sender, senderBalance - amount);
                
                double receiverBalance = balances.getOrDefault(receiver, 0.0);
                balances.put(receiver, receiverBalance + (receiverCents / 100.0));
                
                double proposerBalance = balances.getOrDefault(proposerPubKey, 0.0);
                balances.put(proposerPubKey, proposerBalance + (feeCents / 100.0));
            }
            default -> { return false; }
        }

        // FIX #54: Mark this txId as seen in this block
        seenTxIds.add(txId);
        return true;
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
