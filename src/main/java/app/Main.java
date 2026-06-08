package app;

/**
 * Main — Entry point for the Distributed Blockchain Node.
 *
 * Usage:
 *   java -jar blockchain.jar <nodeIndex> [totalNodes] [rabbitHost] [rabbitPort]
 *
 * Arguments:
 *   nodeIndex  : 0-indexed node number (0, 1, 2, 3, 4, ...)
 *   totalNodes : Total nodes in the testbed (default: 5)
 *   rabbitHost : RabbitMQ host (default: localhost)
 *   rabbitPort : RabbitMQ port (default: 5672)
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
            System.err.println("Usage: java -jar blockchain.jar <nodeIndex> [totalNodes] [rabbitHost] [rabbitPort]");
            System.exit(1);
        }

        int    nodeIndex  = Integer.parseInt(args[0]);
        int    totalNodes = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String rabbitHost = args.length > 2 ? args[2] : "localhost";
        int    rabbitPort = args.length > 3 ? Integer.parseInt(args[3]) : 5672;

        // ── Print startup banner ───────────────────────────────────────────
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Distributed Blockchain — Algorand BFT Consensus     ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  Node       : " + nodeIndex + " / " + (totalNodes - 1) + "                                    ║");
        System.out.println("║  Port       : " + (9000 + nodeIndex) + "                                      ║");
        System.out.println("║  DB         : nodes/node_" + nodeIndex + ".db" + "                          ║");
        System.out.println("║  RabbitMQ   : " + rabbitHost + ":" + rabbitPort + "                            ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        // ── Bootstrap and start ────────────────────────────────────────────
        Node node = new Node(nodeIndex, totalNodes, rabbitHost, rabbitPort);
        node.start();

        // ── Keep main thread alive ─────────────────────────────────────────
        // The ConsensusEngine and NetworkEngine run as daemon threads.
        // Block the main thread to keep the JVM alive until SIGTERM/SIGINT.
        System.out.println("[Main] Node is running. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }
}
