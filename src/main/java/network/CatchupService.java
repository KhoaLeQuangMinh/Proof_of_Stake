package network;

import model.Block;
import model.BlockCertificate;
import state.StateEngine;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CatchupService — Background daemon for secure block synchronization.
 * Fixes Bug E (Consensus loop blocking) and Bug R (Unverified catchup blocks).
 */
public class CatchupService {

    private final NetworkEngine networkEngine;
    private final StateEngine stateEngine;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public CatchupService(NetworkEngine networkEngine, StateEngine stateEngine) {
        this.networkEngine = networkEngine;
        this.stateEngine = stateEngine;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CatchupDaemon");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running) return;
        running = true;
        // Run every 2 seconds
        scheduler.scheduleWithFixedDelay(this::syncLoop, 2, 2, TimeUnit.SECONDS);
        System.out.println("[CatchupService] Started background sync daemon.");
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    private void syncLoop() {
        try {
            long localTip = stateEngine.getLatestRound();
            long networkTip = networkEngine.getNetworkTip();
            long gap = networkTip - localTip;

            if (gap <= 0) return;

            System.out.println("[CatchupService] Gap=" + gap + " detected. Fetching rounds " + (localTip + 1) + " to " + networkTip);

            List<Block> fetchedBlocks = networkEngine.activeFetch(localTip + 1, networkTip);
            if (fetchedBlocks == null || fetchedBlocks.isEmpty()) return;

            for (Block b : fetchedBlocks) {
                // Bug R Fix: Cryptographically verify the block's supermajority certificate before applying!
                BlockCertificate cert = b.getCertificate();
                if (cert == null || !stateEngine.verifyCertificate(cert, b.getRound())) {
                    System.err.println("[CatchupService] WARNING: Rejected uncertified or forged block at round " + b.getRound());
                    break; // Stop syncing on first invalid block
                }

                boolean applied = stateEngine.applyBlock(b);
                if (applied) {
                    System.out.println("[CatchupService] Applied caught-up block: " + b.getRound());
                    // Bug S Fix implicitly covered by saving cert
                    stateEngine.getDb().saveCertificate(cert);
                    
                    networkEngine.setNetworkTip(b.getRound());
                    networkEngine.setNetworkTipHash(b.getHash());
                } else {
                    System.err.println("[CatchupService] Failed to apply block " + b.getRound());
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("[CatchupService] Sync error: " + e.getMessage());
        }
    }
}
