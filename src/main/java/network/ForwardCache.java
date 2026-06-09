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
 * Integration Plan §2.1:
 *  - Map<Round, Map<Step, List<Message>>> ForwardCache: The live memory buffer.
 *  - Used exclusively for consensus messages (VoteMessages), NOT transactions.
 *
 * Fixes applied:
 *  FIX #36/#37 — Iteration-keyed slots for BBA_GOSSIP and COMMON_COIN:
 *   BBA* runs multiple iterations per round. The cache key for BBA messages
 *   is now (round, stepKey) where stepKey = step.name() + ":" + iteration.
 *   This prevents BBA iteration N votes from leaking into iteration N+1's
 *   count window when both happen within the same consensus round.
 *   Helper methods put(round, step, iteration, msg) and poll(round, step, iter, timeout)
 *   are added for BBA steps. Non-BBA steps continue using step.name() directly.
 *
 *  FIX #20 — PROPOSAL step stored separately:
 *   PROPOSAL messages are stored under a dedicated "PROPOSAL" key, separate from
 *   SOFT_VOTE. This prevents proposal metadata (vrfProof, sender) from polluting
 *   Phase 4's soft-vote count window which uses the SOFT_VOTE key.
 */
public class ForwardCache {

    // Cache structure: Round -> StepKey -> BlockingQueue<VoteMessage>
    // StepKey = step.name() for non-BBA steps; step.name() + ":" + iteration for BBA steps.
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, LinkedBlockingQueue<VoteMessage>>> cache
        = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Write (NetworkEngine → ForwardCache)
    // -------------------------------------------------------------------------

    /**
     * Stores a validated VoteMessage for standard (non-BBA) steps.
     * Step key = step.name()
     */
    public void put(long round, VoteMessage.Step step, VoteMessage message) {
        put(round, step.name(), message);
    }

    /**
     * FIX #36/#37: Stores a BBA_GOSSIP or COMMON_COIN message with an iteration key.
     * Step key = step.name() + ":" + iteration
     * This isolates messages from different BBA* iterations within the same round.
     */
    public void putBBA(long round, VoteMessage.Step step, int iteration, VoteMessage message) {
        put(round, step.name() + ":" + iteration, message);
    }

    private void put(long round, String stepKey, VoteMessage message) {
        cache.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
             .computeIfAbsent(stepKey, s -> new LinkedBlockingQueue<>())
             .offer(message);
    }

    // -------------------------------------------------------------------------
    // Read (ConsensusEngine ← ForwardCache)
    // -------------------------------------------------------------------------

    /**
     * Blocking poll for non-BBA steps (PROPOSAL, SOFT_VOTE, CERTIFY_VOTE).
     */
    public VoteMessage poll(long round, VoteMessage.Step step, long timeoutMs) {
        return poll(round, step.name(), timeoutMs);
    }

    /**
     * FIX #36/#37: Blocking poll for BBA_GOSSIP or COMMON_COIN with iteration key.
     * Returns null if the timeout elapses without a message arriving.
     */
    public VoteMessage pollBBA(long round, VoteMessage.Step step, int iteration, long timeoutMs) {
        return poll(round, step.name() + ":" + iteration, timeoutMs);
    }

    private VoteMessage poll(long round, String stepKey, long timeoutMs) {
        try {
            LinkedBlockingQueue<VoteMessage> queue =
                cache.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                     .computeIfAbsent(stepKey, s -> new LinkedBlockingQueue<>());
            return queue.poll(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Drains all currently available messages for a Round/Step without waiting.
     * For non-BBA steps.
     */
    public List<VoteMessage> drain(long round, VoteMessage.Step step) {
        return drain(round, step.name());
    }

    /**
     * FIX #36/#37: Drains all messages for a specific BBA iteration.
     */
    public List<VoteMessage> drainBBA(long round, VoteMessage.Step step, int iteration) {
        return drain(round, step.name() + ":" + iteration);
    }

    private List<VoteMessage> drain(long round, String stepKey) {
        List<VoteMessage> result = new ArrayList<>();
        LinkedBlockingQueue<VoteMessage> queue =
            cache.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                 .computeIfAbsent(stepKey, s -> new LinkedBlockingQueue<>());
        queue.drainTo(result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Proposal Block Cache (FIX #18)
    // -------------------------------------------------------------------------

    /**
     * FIX #18/#20: Dedicated store for full Block payloads from PROPOSAL messages.
     * Round -> ProposerPubKey -> Block JSON
     * This allows non-proposing nodes to retrieve the actual block payload for the
     * Lowest-VRF-Hash proposal without re-gossipping the entire block.
     * Stored separately from VoteMessages so proposal data doesn't pollute vote counts.
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, String>> proposalBlocks
        = new ConcurrentHashMap<>();

    /** Stores the full block JSON for a given round and proposer's pubKey. */
    public void putProposalBlock(long round, String proposerPubKey, String blockJson) {
        proposalBlocks.computeIfAbsent(round, r -> new ConcurrentHashMap<>())
                      .put(proposerPubKey, blockJson);
    }

    /** Retrieves the full block JSON for a specific proposer in a round. Returns null if not found. */
    public String getProposalBlock(long round, String proposerPubKey) {
        ConcurrentHashMap<String, String> roundMap = proposalBlocks.get(round);
        return (roundMap != null) ? roundMap.get(proposerPubKey) : null;
    }

    /** Returns all proposal block entries for a round. Key = proposerPubKey, Value = blockJson. */
    public ConcurrentHashMap<String, String> getAllProposalBlocks(long round) {
        return proposalBlocks.getOrDefault(round, new ConcurrentHashMap<>());
    }

    // -------------------------------------------------------------------------
    // Garbage Collection
    // -------------------------------------------------------------------------

    /**
     * Purges all cache entries for rounds older than currentRound.
     * Called at the end of every round (Phase 6) to prevent RAM leaks.
     */
    public void purge(long currentRound) {
        cache.keySet().removeIf(round -> round < currentRound);
        proposalBlocks.keySet().removeIf(round -> round < currentRound);
    }

    /** Returns the number of distinct rounds currently buffered (for monitoring). */
    public int getBufferedRoundCount() {
        return cache.size();
    }
}
