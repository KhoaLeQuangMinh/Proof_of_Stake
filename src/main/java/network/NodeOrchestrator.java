package network;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import app.Wallet;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NodeOrchestrator - Standalone HTTP Server to dynamically spawn blockchain nodes.
 *
 * It exposes `POST /api/orchestrator/spawn_node` which:
 * 1. Derives a new node's deterministic wallet (Public Key & Address).
 * 2. Spawns an entirely independent OS-level process (`java -cp ... app.Main`).
 * 3. Returns the derived keys to the backend.
 */
public class NodeOrchestrator {

    // Start assigning node indexes at 0 to align with testbed genesis nodes 0-9
    private static final AtomicInteger nextNodeIndex = new AtomicInteger(0);

    public static void main(String[] args) throws IOException {
        int port = 9000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/orchestrator/spawn_node", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        int nodeIndex = nextNodeIndex.getAndIncrement();
                        
                        // 1. Generate identity deterministically exactly like Node.java does
                        Wallet wallet = Wallet.fromSeedPhrase("GENESIS_NODE_" + (nodeIndex + 1));
                        String pubKey = wallet.getPublicKeyBase64();
                        String address = wallet.getAddress();
                        int nodePort = 9000 + nodeIndex;

                        // 2. Spawn the process independently
                        // Usage: java -jar blockchain.jar <nodeIndex> [totalNodes]
                        ProcessBuilder pb = new ProcessBuilder(
                            "java", "-cp", "target/blockchain.jar",
                            "app.Main", String.valueOf(nodeIndex), "10"
                        );
                        
                        // Redirect output to a log file so it runs completely detached
                        pb.redirectErrorStream(true);
                        pb.redirectOutput(new File("node_" + nodeIndex + "_orchestrated.log"));
                        
                        // Start process completely independently
                        Process process = pb.start();
                        System.out.println("[Orchestrator] Spawned Node " + nodeIndex + " on port " + nodePort + " (PID " + process.pid() + ")");

                        // 3. Return JSON response to the backend
                        String jsonResponse = String.format(
                            "{\n  \"status\": \"success\",\n  \"nodeIndex\": %d,\n  \"port\": %d,\n  \"publicKey\": \"%s\",\n  \"address\": \"%s\"\n}",
                            nodeIndex, nodePort, pubKey, address
                        );

                        byte[] responseBytes = jsonResponse.getBytes();
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, responseBytes.length);
                        OutputStream os = exchange.getResponseBody();
                        os.write(responseBytes);
                        os.close();

                    } catch (Exception e) {
                        e.printStackTrace();
                        String error = "{\"error\": \"" + e.getMessage() + "\"}";
                        exchange.sendResponseHeaders(500, error.getBytes().length);
                        OutputStream os = exchange.getResponseBody();
                        os.write(error.getBytes());
                        os.close();
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                }
            }
        });

        server.setExecutor(null); // creates a default executor
        server.start();
        System.out.println("Node Orchestrator started on port " + port);
    }
}
