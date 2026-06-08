package consensus;

/**
 * Sortition — Binomial Distribution CDF for VRF-based committee election.
 *
 * Integration Plan §5.2 Phase 1 (Value Propose):
 *  "Use the Binomial Distribution CDF: B(k; w, p) where w is your total
 *   micro-token stake, and p = Expected_Proposers / Total_Online_Stake.
 *   The VRF hash determines k (the number of sub-user seats won)."
 *
 * Mathematical Model:
 *  - p = expectedCommitteeSize / totalOnlineStake
 *    This gives the probability that any single token "wins" a seat.
 *  - The VRF hash is converted to a uniform fraction [0, 1).
 *  - The Binomial CDF B(k; w, p) is evaluated for k = 0, 1, 2, ...
 *    until the CDF exceeds the hash fraction.
 *  - The resulting k is the number of seats ("votes") this node wins.
 *
 * Why Not a Simpler Method?
 *  Simple division (myStake / totalStake * expectedCommittee) can be
 *  manipulated by attackers who know others' stakes. The Binomial
 *  Distribution + VRF is unpredictable without knowing the private key.
 *
 * Committee Sizes (per integrated plan):
 *  - Phase 1 (Proposers): expectedCommitteeSize = 20
 *  - Phase 3 (Voters):    expectedCommitteeSize = 1000
 *
 * Threshold (per integrated plan):
 *  - A vote passes ONLY if a specific hash hits > 68% of expectedCommitteeSize.
 *  - For voters: threshold = 680 (68% of 1000).
 */
public class Sortition {

    // -------------------------------------------------------------------------
    // Committee Size Constants
    // -------------------------------------------------------------------------

    /** Expected number of proposers per round (Phase 1). */
    public static final int EXPECTED_PROPOSERS = 20;

    /** Expected number of voters per round (Phase 3 + Phase 5 BBA*). */
    public static final int EXPECTED_VOTERS = 1000;

    /**
     * Threshold for a vote to pass: > 68% of expected voter committee.
     * Derived from Byzantine Fault Tolerance: with up to 1/3 malicious nodes,
     * a 2/3 supermajority guarantees safety. We use 68% which exceeds 2/3.
     */
    public static final int VOTE_THRESHOLD = (int)(0.68 * EXPECTED_VOTERS); // 680

    // -------------------------------------------------------------------------
    // Core Sortition Function
    // -------------------------------------------------------------------------

    /**
     * Determines how many sortition "seats" (votes) a node wins for a given VRF hash.
     *
     * Integration Plan §5.2 Phase 1:
     *  "Do NOT use simple division. You MUST use the Binomial Distribution CDF."
     *
     * Algorithm:
     *  1. Convert the VRF hash bytes to a uniform fraction f ∈ [0, 1).
     *  2. p = expectedCommitteeSize / totalOnlineStake (probability per token).
     *  3. Evaluate Binomial CDF B(k; myStake, p) for k = 0, 1, 2, ...
     *  4. Find the first k where CDF(k) > f. That k is the number of seats won.
     *
     * @param vrfHashBytes        The first 8 bytes of the VRF hash (uniform randomness).
     * @param myStake             This node's stake (in token units, NOT microTokens).
     * @param totalOnlineStake    The total network-wide online stake at R-320.
     * @param expectedCommittee   Target committee size (20 for proposers, 1000 for voters).
     * @return Number of sortition seats won (0 = not selected this round).
     */
    public static int getSortitionWeight(byte[] vrfHashBytes,
                                         long myStake,
                                         long totalOnlineStake,
                                         int expectedCommittee) {
        if (myStake <= 0 || totalOnlineStake <= 0) return 0;

        // p = probability that a single token wins a seat
        double p = (double) expectedCommittee / (double) totalOnlineStake;
        if (p > 1.0) p = 1.0;  // clamp for extremely small networks

        // Convert VRF hash bytes to a uniform fraction [0, 1)
        double hashFraction = convertHashToFraction(vrfHashBytes);

        // Evaluate the Binomial CDF to find the number of seats won
        double cumulativeProb = 0.0;
        for (int k = 0; k <= myStake && k <= 10_000; k++) {
            double probK = binomialPMF(myStake, k, p);
            cumulativeProb += probK;
            if (hashFraction < cumulativeProb) {
                return k;
            }
        }

        return 0; // Didn't win any seats (extremely unlikely with large stake)
    }

    // -------------------------------------------------------------------------
    // Threshold Checks
    // -------------------------------------------------------------------------

    /**
     * Returns true if the total accumulated weight in Phase 3/4/5 reaches
     * the supermajority threshold (> 68% of expected voter committee).
     *
     * @param totalWeight The sum of sortition weights from all collected votes.
     * @return true if consensus threshold is crossed.
     */
    public static boolean hasReachedThreshold(int totalWeight) {
        return totalWeight > VOTE_THRESHOLD;
    }

    // -------------------------------------------------------------------------
    // Binomial Distribution Mathematics
    // -------------------------------------------------------------------------

    /**
     * Binomial Probability Mass Function: P(X = k) = C(n,k) * p^k * (1-p)^(n-k)
     * Computed in log-space to avoid floating point overflow with large n.
     *
     * @param n Total number of trials (myStake).
     * @param k Number of successes (seats).
     * @param p Probability of success per trial.
     * @return P(X = k) for Binomial(n, p).
     */
    static double binomialPMF(long n, int k, double p) {
        if (k < 0 || k > n) return 0.0;
        if (p == 0.0) return (k == 0) ? 1.0 : 0.0;
        if (p == 1.0) return (k == n) ? 1.0 : 0.0;

        // Use log-space arithmetic to avoid overflow for large n
        double logProb = logCombinations(n, k)
                       + k * Math.log(p)
                       + (n - k) * Math.log(1.0 - p);
        return Math.exp(logProb);
    }

    /**
     * Log of binomial coefficient C(n, k) using the Log-Gamma function.
     * ln(C(n,k)) = lnΓ(n+1) - lnΓ(k+1) - lnΓ(n-k+1)
     */
    private static double logCombinations(long n, int k) {
        return logGamma(n + 1) - logGamma(k + 1) - logGamma(n - k + 1);
    }

    /**
     * Lanczos approximation for the Log-Gamma function.
     * Numerically stable for large positive x values.
     */
    private static double logGamma(double x) {
        double[] lanczos = {
            0.99999999999980993,
            676.5203681218851,
            -1259.1392167224028,
            771.32342877765313,
            -176.61502916214059,
            12.507343278220815,
            -0.13857109526572012,
            9.9843695780195716e-6,
            1.5056327351493116e-7
        };

        if (x < 0.5) {
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        }

        x -= 1.0;
        double a = lanczos[0];
        for (int i = 1; i < lanczos.length; i++) {
            a += lanczos[i] / (x + i);
        }

        double t = x + 7.5; // g = 7
        return 0.5 * Math.log(2.0 * Math.PI)
             + (x + 0.5) * Math.log(t)
             - t
             + Math.log(a);
    }

    // -------------------------------------------------------------------------
    // Hash Fraction Conversion
    // -------------------------------------------------------------------------

    /**
     * Converts the first 8 bytes of a VRF hash into a uniform fraction [0, 1).
     *
     * Uses 64 bits of the hash for high precision. The unsigned 64-bit value
     * is divided by 2^64 to map it to [0, 1).
     *
     * @param hashBytes The raw VRF hash bytes (at least 8 bytes).
     * @return A double in [0, 1.0).
     */
    static double convertHashToFraction(byte[] hashBytes) {
        if (hashBytes == null || hashBytes.length == 0) return 0.0;

        long value = 0;
        int bytesToUse = Math.min(8, hashBytes.length);
        for (int i = 0; i < bytesToUse; i++) {
            value = (value << 8) | (hashBytes[i] & 0xFF);
        }

        // Convert unsigned long to double fraction
        // Java longs are signed, so we handle the sign bit carefully
        double fraction = (value >= 0)
                        ? (double) value
                        : (double) value + 18446744073709551616.0; // 2^64

        return fraction / 18446744073709551616.0; // Divide by 2^64
    }
}
