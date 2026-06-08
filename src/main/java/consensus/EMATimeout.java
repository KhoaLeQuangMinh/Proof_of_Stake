package consensus;

/**
 * EMATimeout — Dynamic Adaptive Timeout for consensus phases.
 *
 * Integration Plan §5.2 Phase 4 (Resolving Filter):
 *  The node does NOT use a hardcoded timer (e.g., Thread.sleep(5000)).
 *  Instead, it uses a Dynamic Adaptive Timeout based on Exponential Moving Average.
 *
 * Formula (from the integrated plan):
 *  Timeout = max(2000ms, min(Previous_Round_Duration * 1.5, 10000ms))
 *
 * How the EMA Works:
 *  - Each round's actual completion time is recorded via recordRoundDuration().
 *  - The EMA smooths out network spikes (outlier congestion events don't break consensus).
 *  - The EMA then predicts how long the NEXT round will take.
 *  - The timeout is clamped between MIN_TIMEOUT (2.0s) and MAX_TIMEOUT (10.0s).
 *
 * Effect on the System:
 *  - Fast internet (500ms rounds): timeout shrinks to 2000ms. Very fast blocks.
 *  - Heavy congestion (7s rounds): timeout expands to 10000ms. Network breathes.
 *  - No matter what, the system always makes progress within 10 seconds per phase.
 *
 * Usage:
 *  EMATimeout ema = new EMATimeout();
 *  long deadline = System.currentTimeMillis() + ema.getNextTimeoutMs();
 *  while (System.currentTimeMillis() < deadline) {
 *      // poll for messages
 *  }
 *  ema.recordRoundDuration(System.currentTimeMillis() - startTime);
 */
public class EMATimeout {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Minimum timeout regardless of observed network speed (2 seconds). */
    private static final long   MIN_TIMEOUT_MS = 2_000L;

    /** Maximum timeout during severe congestion (10 seconds). */
    private static final long   MAX_TIMEOUT_MS = 10_000L;

    /**
     * Initial EMA value on node startup.
     * Starts at 4 seconds — a reasonable "warm-up" assumption.
     */
    private static final double INITIAL_EMA_MS = 4_000.0;

    /**
     * EMA smoothing factor (alpha).
     * Higher alpha = more weight on recent rounds (faster adaptation).
     * Lower alpha = smoother but slower to react.
     * 0.3 is a balanced value from network latency literature.
     */
    private static final double ALPHA = 0.3;

    /**
     * Growth multiplier for the timeout beyond the observed EMA.
     * Per the integrated plan: Timeout = min(Previous_Round_Duration * 1.5, MAX)
     * This 1.5x headroom prevents false timeouts due to random network jitter.
     */
    private static final double GROWTH_FACTOR = 1.5;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The current Exponential Moving Average of round durations (in milliseconds). */
    private double ema = INITIAL_EMA_MS;

    // -------------------------------------------------------------------------
    // Core API
    // -------------------------------------------------------------------------

    /**
     * Returns the recommended timeout for the next consensus phase in milliseconds.
     *
     * Algorithm:
     *  1. Compute candidate = ema * GROWTH_FACTOR (add headroom above observed avg).
     *  2. Clamp: result = max(MIN_TIMEOUT, min(candidate, MAX_TIMEOUT)).
     *
     * @return Timeout in milliseconds (always between 2000 and 10000).
     */
    public long getNextTimeoutMs() {
        double candidate = ema * GROWTH_FACTOR;
        return (long) Math.min(MAX_TIMEOUT_MS, Math.max(MIN_TIMEOUT_MS, candidate));
    }

    /**
     * Records the actual duration of a completed round to update the EMA.
     *
     * Call this at the END of each round (after Phase 6 completes) so the
     * next round's timeout reflects the real-world propagation speed.
     *
     * EMA formula: EMA_new = alpha * duration + (1 - alpha) * EMA_old
     *
     * @param durationMs The actual wall-clock duration of the completed round (ms).
     */
    public void recordRoundDuration(long durationMs) {
        if (durationMs <= 0) return;
        ema = ALPHA * durationMs + (1.0 - ALPHA) * ema;
        System.out.println("[EMATimeout] Round took " + durationMs + "ms | EMA=" +
                           String.format("%.0f", ema) + "ms | next=" + getNextTimeoutMs() + "ms");
    }

    /**
     * Returns the current EMA value in milliseconds (for monitoring/debugging).
     */
    public long getCurrentEmaMs() {
        return (long) ema;
    }

    /**
     * Resets the EMA back to the initial value.
     * Called when the node reconnects after a long partition or restart.
     */
    public void reset() {
        ema = INITIAL_EMA_MS;
    }
}
