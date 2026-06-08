package network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TokenBucketRateLimiter — IP-level DDoS protection.
 *
 * Integration Plan §2.2 ingressLoop Step 0 (DDoS Protection):
 *  - Check IP rate limits. If an IP exceeds payload thresholds, DROP and ban.
 *  - Prevents attackers from exhausting the CPU with invalid signatures.
 *
 * Algorithm: Token Bucket.
 *  - Each IP address has a "bucket" of tokens.
 *  - Tokens are added at a fixed REFILL_RATE per second (up to MAX_TOKENS).
 *  - Each incoming message costs 1 token.
 *  - If an IP has 0 tokens, the message is dropped and the IP is flagged.
 *  - If an IP burns through its bucket too quickly (burst attack), it is
 *    temporarily banned to prevent CPU exhaustion.
 *
 * Design Note:
 *  This operates purely in memory. It is intentionally non-persistent —
 *  if the node restarts, rate-limit state is cleared. For production,
 *  a shared Redis-backed rate limiter would be used.
 */
public class TokenBucketRateLimiter {

    // Maximum tokens an IP can accumulate (burst capacity)
    private static final double MAX_TOKENS   = 100.0;

    // Token refill rate per second
    private static final double REFILL_RATE  = 10.0;

    // Ban duration in milliseconds (1 minute)
    private static final long   BAN_DURATION_MS = 60_000L;

    // -------------------------------------------------------------------------
    // Per-IP Bucket State
    // -------------------------------------------------------------------------

    private static class Bucket {
        double tokens;
        long   lastRefillTime;
        long   bannedUntil;

        Bucket() {
            this.tokens        = MAX_TOKENS;
            this.lastRefillTime = System.currentTimeMillis();
            this.bannedUntil   = 0;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Core Check
    // -------------------------------------------------------------------------

    /**
     * Checks whether an incoming request from the given IP address is allowed.
     *
     * @param ipAddress The remote IP address of the incoming connection.
     * @return true if the request is allowed (has tokens), false if dropped.
     */
    public synchronized boolean allow(String ipAddress) {
        Bucket bucket = buckets.computeIfAbsent(ipAddress, k -> new Bucket());

        long now = System.currentTimeMillis();

        // Check if still banned
        if (now < bucket.bannedUntil) {
            return false;
        }

        // Refill tokens based on elapsed time
        double elapsed = (now - bucket.lastRefillTime) / 1000.0;
        bucket.tokens = Math.min(MAX_TOKENS, bucket.tokens + elapsed * REFILL_RATE);
        bucket.lastRefillTime = now;

        // Check if there are tokens available
        if (bucket.tokens < 1.0) {
            // Bucket empty — ban this IP temporarily
            bucket.bannedUntil = now + BAN_DURATION_MS;
            System.out.println("[RateLimit] Banning IP for " + (BAN_DURATION_MS / 1000) + "s: " + ipAddress);
            return false;
        }

        // Consume a token and allow
        bucket.tokens -= 1.0;
        return true;
    }

    /**
     * Explicitly bans an IP address (used when a bad signature is detected).
     * Per ingressLoop Step 2: If Ed25519 signature is invalid, DROP and ban IP.
     *
     * @param ipAddress The IP to ban.
     */
    public synchronized void ban(String ipAddress) {
        Bucket bucket = buckets.computeIfAbsent(ipAddress, k -> new Bucket());
        bucket.bannedUntil = System.currentTimeMillis() + BAN_DURATION_MS;
        bucket.tokens = 0;
        System.out.println("[RateLimit] Explicitly banned IP: " + ipAddress);
    }

    /**
     * Checks if an IP is currently banned.
     */
    public boolean isBanned(String ipAddress) {
        Bucket bucket = buckets.get(ipAddress);
        return bucket != null && System.currentTimeMillis() < bucket.bannedUntil;
    }
}
