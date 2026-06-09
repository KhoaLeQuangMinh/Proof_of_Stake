package app;

/**
 * Main — Entry point for the Distributed Blockchain Node.
 *
 * Usage:
 *   java -jar blockchain.jar <nodeIndex> [totalNodes] [mempoolApiUrl] [eventBusUrl]
 *
 * Arguments:
 *   nodeIndex    : 0-indexed node number (0, 1, 2, 3, 4, ...)
 *   totalNodes   : Total nodes in the testbed (default: 5)
 *   mempoolApiUrl: TransactionBuffer backend API URL (default: http://localhost:8080)
 *   eventBusUrl  : EventBus webhook URL (default: http://localhost:8081/eventbus)
 *
 * Example (5-node local testbed in separate terminals):
 *   java -jar blockchain.jar 0 5
 *   java -jar blockchain.jar 1 5
 *   java -jar blockchain.jar 2 5
 *   java -jar blockchain.jar 3 5
 *   java -jar blockchain.jar 4 5
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // ── Parse arguments ────────────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Usage: java -jar blockchain.jar <nodeIndex> [totalNodes] [mempoolApiUrl] [eventBusUrl]");
            System.exit(1);
        }

        int    nodeIndex     = Integer.parseInt(args[0]);
        int    totalNodes    = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String mempoolApiUrl = args.length > 2 ? args[2] : "http://localhost:8080";
        String eventBusUrl   = args.length > 3 ? args[3] : "http://localhost:8081/eventbus";

        // ── Print startup banner ───────────────────────────────────────────
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Distributed Blockchain — Algorand BFT Consensus     ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  Node       : " + nodeIndex + " / " + (totalNodes - 1) + "                                    ║");
        System.out.println("║  Port       : " + (9000 + nodeIndex) + "                                      ║");
        System.out.println("║  DB         : nodes/node_" + nodeIndex + ".db" + "                          ║");
        System.out.println("║  Mempool API: " + mempoolApiUrl + "                        ║");
        System.out.println("║  EventBus   : " + eventBusUrl + "                 ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        // ── Bootstrap and start ────────────────────────────────────────────
        Node node = new Node(nodeIndex, totalNodes, mempoolApiUrl, eventBusUrl);
        node.start();

        // ── Keep main thread alive ─────────────────────────────────────────
        // The ConsensusEngine and NetworkEngine run as daemon threads.
        // Block the main thread to keep the JVM alive until SIGTERM/SIGINT.
        System.out.println("[Main] Node is running. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
