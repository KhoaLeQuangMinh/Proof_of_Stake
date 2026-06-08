package network;

import model.VoteMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ForwardCache — Thread-safe in-memory message buffer for live consensus.
 *
 * Integration Plan §2.1 (Network Engine State Variables):
 *  - Map<Round, Map<Step, List<Message>>> ForwardCache: The live memory buffer.
 *  - Used exclusively for consensus messages (VoteMessages), NOT transactions.
 *    P2P transaction gossiping is removed — transactions flow via SharedBuffer.
 *  - The NetworkEngine writes to the ForwardCache as valid messages arrive.
 *  - The ConsensusEngine reads from the ForwardCache during each phase.
 *
 * Threading Model:
 *  - NetworkEngine (multiple reader threads) → writes via put()
 *  - ConsensusEngine (single main loop thread) → reads via poll() or drain()
 *  - ConcurrentHashMap + LinkedBlockingQueue ensures thread-safe access.
 *
 * Memory Management:
 *  - purge(currentRound) must be called at the start of each new round to
 *    delete cache entries for past rounds, preventing RAM leaks over time.
 *  - Also enforced by the 10-block rule in ingressLoop: only messages for
 *    rounds <= Network_Tip + 10 are ever stored here.
 */
public class ForwardCache {

    // Cache structure: Round -> Step -> BlockingQueue<VoteMessage>
    // LinkedBlockingQueue supports the poll(timeout) pattern needed for EMA timeouts.
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, LinkedBlockingQueue<VoteMessage>>> cache
        = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Write (NetworkEngine → ForwardCache)
    // -------------------------------------------------------------------------

    /**
     * Stores a validated VoteMessage into the cache at the correct Round/Step slot.
     * Called by the NetworkEngine after a message passes all ingressLoop checks.
     *
     * @param round   The consensus round from the message.
     * @param step    The consensus step (SOFT_VOTE, BBA_GOSSIP, etc.) as a string.
     * @param message The validated VoteMessage to store.
     */
    public void put(long round, String step, VoteMessage message) {
        cache.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
             .computeIfAbsent(step, s -> new LinkedBlockingQueue<>())
             .offer(message);
    }

    /**
     * Convenience overload using the Step enum.
     */
    public void put(long round, VoteMessage.Step step, VoteMessage message) {
        put(round, step.name(), message);
    }

    // -------------------------------------------------------------------------
    // Read (ConsensusEngine ← ForwardCache)
    // -------------------------------------------------------------------------

    /**
     * Blocking poll: waits up to timeoutMs for the next message at Round/Step.
     * Returns null if the timeout elapses without a message arriving.
     *
     * This is the primary read pattern used inside each consensus phase.
     * The ConsensusEngine calls this in a tight loop until its EMA deadline passes:
     *
     *   long deadline = System.currentTimeMillis() + emaTimeout.getNextTimeoutMs();
     *   while (System.currentTimeMillis() < deadline) {
     *       long remaining = deadline - System.currentTimeMillis();
     *       VoteMessage vote = forwardCache.poll(round, step, remaining);
     *       if (vote != null) processVote(vote);
     *   }
     *
     * @param round     The consensus round to read from.
     * @param step      The consensus step to read from.
     * @param timeoutMs Maximum milliseconds to wait.
     * @return A VoteMessage if one arrives before timeout, or null.
     */
    public VoteMessage poll(long round, VoteMessage.Step step, long timeoutMs) {
        try {
            LinkedBlockingQueue<VoteMessage> queue =
                cache.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                     .computeIfAbsent(step.name(), s -> new LinkedBlockingQueue<>());
            return queue.poll(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Drains all currently available messages for a Round/Step without waiting.
     * Used at the start of a phase to collect messages that arrived during
     * a previous phase's execution window.
     *
     * @param round The consensus round to read from.
     * @param step  The consensus step to read from.
     * @return All currently buffered VoteMessages for this slot (may be empty).
     */
    public List<VoteMessage> drain(long round, VoteMessage.Step step) {
        List<VoteMessage> result = new ArrayList<>();
        LinkedBlockingQueue<VoteMessage> queue =
            cache.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                 .computeIfAbsent(step.name(), s -> new LinkedBlockingQueue<>());
        queue.drainTo(result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Garbage Collection
    // -------------------------------------------------------------------------

    /**
     * Purges all cache entries for rounds older than currentRound.
     *
     * Integration Plan §2.2 (purgeOldCache):
     *  The Garbage Collector. Called at the end of every round (Phase 6).
     *  Prevents RAM leaks: if a node runs for 10 years, old round entries
     *  are instantly deleted the millisecond the round advances.
     *
     * @param currentRound The current consensus round. All entries for
     *                     rounds < currentRound are permanently deleted.
     */
    public void purge(long currentRound) {
        cache.keySet().removeIf(round -> round < currentRound);
    }

    /**
     * Returns the number of distinct rounds currently buffered.
     * Useful for debugging and monitoring memory usage.
     */
    public int getBufferedRoundCount() {
        return cache.size();
    }
}
