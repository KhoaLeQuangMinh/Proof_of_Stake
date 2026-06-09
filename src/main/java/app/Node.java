package app;

import buffer.EventGateway;
import buffer.SharedBuffer;
import consensus.ConsensusEngine;
import model.BlockCertificate;
import network.NetworkEngine;
import state.DatabaseManager;
import state.StateEngine;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Node — The top-level bootstrap class for a single Participation Node.
 *
 * Integration Plan §1.3 (Node Architecture):
 *  This class wires all 4 components together and manages the startup sequence.
 *
 * Component Wiring:
 *  SharedBuffer (RabbitMQ) <-- upstream clients
 *       |
 *  NetworkEngine (TCP P2P)  <----> peers
 *       |
 *  StateEngine (SQLite + PendingStateOverlay)
 *       |
 *  ConsensusEngine (BBA* main loop)
 *       |
 *  EventGateway (Singleton EventBus hooks)
 *
 * Threading:
 *  - NetworkEngine runs on a CachedThreadPool (IO threads).
 *  - ConsensusEngine runs on its own daemon thread.
 *  - The main thread blocks on System.in (or a shutdown hook).
 */
public class Node {

    private final int nodeIndex;
    private final int listenPort;
    private final String dbPath;
    private final String mempoolApiUrl;
    private final String eventBusUrl;
    private final int    totalNodes;

    // The 4 core components
    private NetworkEngine   networkEngine;
    private StateEngine     stateEngine;
    private SharedBuffer    sharedBuffer;
    private ConsensusEngine consensusEngine;
    private network.CatchupService catchupService;

    // The certificate interrupt queue (shared between NetworkEngine and ConsensusEngine)
    private final LinkedBlockingQueue<BlockCertificate> certificateQueue = new LinkedBlockingQueue<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param nodeIndex     0-indexed node number (e.g., 0, 1, 2, 3, 4)
     * @param totalNodes    Total number of nodes in the testbed (for seed peer list)
     * @param mempoolApiUrl HTTP URL of the TransactionBuffer Spring Boot backend
     * @param eventBusUrl   HTTP URL of the EventBus webhook endpoint
     */
    public Node(int nodeIndex, int totalNodes, String mempoolApiUrl, String eventBusUrl) {
        this.nodeIndex     = nodeIndex;
        this.totalNodes    = totalNodes;
        this.listenPort    = 9000 + nodeIndex;
        this.dbPath        = "nodes/node_" + nodeIndex + ".db";
        this.mempoolApiUrl = mempoolApiUrl;
        this.eventBusUrl   = eventBusUrl;
    }

    // -------------------------------------------------------------------------
    // Bootstrap
    // -------------------------------------------------------------------------

    /**
     * Full node bootstrap sequence:
     * 1. Initialize SQLite database.
     * 2. Load or generate the node's Ed25519 Wallet.
     * 3. Inject genesis state if this is the first boot.
     * 4. Initialize the 4 components.
     * 5. Wire components together (inject references).
     * 6. Start all components.
     */
    public void start() throws Exception {
        System.out.println("\n[Node " + nodeIndex + "] ==================== STARTING ====================");
        System.out.println("[Node " + nodeIndex + "] Port=" + listenPort + " DB=" + dbPath);

        // ── Step 1: Database ─────────────────────────────────────────────────
        DatabaseManager db = new DatabaseManager(dbPath);
        db.initialize();

        // ── Step 2: Node Identity (Wallet) ───────────────────────────────────
        Wallet wallet = Wallet.loadFromDB(db);
        if (wallet == null) {
            // Use deterministic genesis wallet for testbed nodes
            // (in production, generateAndPersist() would be used for new nodes)
            wallet = Wallet.fromSeedPhrase("GENESIS_NODE_" + (nodeIndex + 1));
            db.saveNodeIdentity(wallet.getAddress(), wallet.getPublicKeyBase64(),
                                crypto.Ed25519Util.encodePrivateKey(wallet.getPrivateKey()));
            System.out.println("[Node " + nodeIndex + "] Loaded genesis identity.");
        }

        // ── Step 3: Genesis Injection (first boot only) ──────────────────────
        if (db.getLatestRound() < 0) {
            System.out.println("[Node " + nodeIndex + "] FIRST BOOT — injecting genesis state.");
            model.Block genesisBlock = GenesisConfig.buildGenesisBlock();
            List<GenesisConfig.GenesisAccount> genesisAccounts =
                GenesisConfig.getGenesisAccounts(totalNodes);
            db.injectGenesis(genesisAccounts, genesisBlock);
        }

        // ── Step 4: EventGateway (Singleton) ─────────────────────────────────
        EventGateway eventGateway = EventGateway.getInstance();
        eventGateway.setEventBusUrl(eventBusUrl);

        // ── Step 5: StateEngine ──────────────────────────────────────────────
        stateEngine = new StateEngine(db, eventGateway);

        // ── Step 6: NetworkEngine ─────────────────────────────────────────────
        List<String> seedPeers = GenesisConfig.getSeedPeers(totalNodes, listenPort);
        networkEngine = new NetworkEngine(
            listenPort,
            wallet.getPublicKeyBase64(),
            wallet.getPrivateKey(),
            seedPeers,
            certificateQueue
        );

        // ── Step 7: SharedBuffer (HTTP API) ──────────────────────────────────
        sharedBuffer = new SharedBuffer(mempoolApiUrl, eventGateway);

        // ── Step 8: Wire SharedBuffer into NetworkEngine ──────────────────────
        // So the NetworkEngine can notify SharedBuffer of CertificateEvents
        networkEngine.setSharedBuffer(sharedBuffer);

        // ── Step 8b: Wire StateEngine into NetworkEngine ───────────────────────
        // FIX #13/#14: Allows NetworkEngine to serve SYNC_RESPONSE blocks from DB
        networkEngine.setStateEngine(stateEngine);

        // ── Step 9: ConsensusEngine ───────────────────────────────────────────
        consensusEngine = new ConsensusEngine(
            stateEngine,
            networkEngine,
            sharedBuffer,
            eventGateway,
            wallet,
            certificateQueue
        );

        // ── Step 9b: CatchupService ───────────────────────────────────────────
        catchupService = new network.CatchupService(networkEngine, stateEngine);

        // ── Step 10: Start all components (order matters) ─────────────────────
        networkEngine.start();              // P2P layer must be up before consensus
        sharedBuffer.connect();             // Connect to RabbitMQ and pull first batch
        catchupService.start();             // Start daemon to sync blocks
        consensusEngine.start();            // Start the BBA* main loop

        System.out.println("[Node " + nodeIndex + "] ==================== RUNNING ====================\n");

        // Register shutdown hook for graceful stop
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "ShutdownHook"));
    }

    /** Graceful shutdown of all components. */
    public void stop() {
        System.out.println("[Node " + nodeIndex + "] Shutting down...");
        if (consensusEngine != null) consensusEngine.stop();
        if (catchupService  != null) catchupService.stop();
        if (networkEngine   != null) networkEngine.stop();
        if (sharedBuffer    != null) sharedBuffer.stop();
    }
}
