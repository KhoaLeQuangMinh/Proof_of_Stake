package buffer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import model.BlockCertificate;
import model.Transaction;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SharedBuffer — Component 2: The Centralized Transaction Sequencer.
 *
 * Solution A (Phase 6 One-Shot Deletion):
 *  - Pulls a batch from the Spring Boot mempool API via HTTP GET.
 *  - No RabbitMQ AMQP client used locally.
 *  - Both ackCurrentBatch() and nackCurrentBatch() call HTTP POST /api/mempool/confirm
 *    with the captured batchId to guarantee one-shot semantics.
 */
public class SharedBuffer {

    private final String mempoolApiUrl;
    private final EventGateway eventGateway;
    private final HttpClient httpClient;

    private volatile List<Transaction> currentBatch = new ArrayList<>();

    private volatile long lastHandledRound = -1L;
    private volatile boolean running = false;
    private static final Gson GSON = new Gson();

    public SharedBuffer(String mempoolApiUrl, EventGateway eventGateway) {
        this.mempoolApiUrl = mempoolApiUrl;
        this.eventGateway = eventGateway;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void connect() throws Exception {
        running = true;
        System.out.println("[SharedBuffer] Initialized HTTP Client. Backend: " + mempoolApiUrl);
        fetchNextBatch();
    }

    public void stop() {
        running = false;
    }

    public synchronized List<Transaction> captureRoundBatch() {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            fetchNextBatchSync();
            if (currentBatch.size() >= 10) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<Transaction> capturedBatch = new ArrayList<>(currentBatch);
        // Clear local batch so we can fetch fresh ones if needed next round
        currentBatch = new ArrayList<>();
        return Collections.unmodifiableList(capturedBatch);
    }

    public synchronized void confirmTxs(List<String> txIds) {
        if (txIds == null || txIds.isEmpty()) return;

        try {
            JsonArray txIdsArray = new JsonArray();
            for (String txId : txIds) {
                txIdsArray.add(txId);
            }
            JsonObject payload = new JsonObject();
            payload.add("txIds", txIdsArray);
            String jsonBody = GSON.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mempoolApiUrl + "/api/mempool/confirm_txs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[SharedBuffer] Confirmed " + txIds.size() + " txs | Status=" + response.statusCode());
            if (running) fetchNextBatch();
        } catch (Exception e) {
            System.err.println("[SharedBuffer] Failed to confirm txs: " + e.getMessage());
        }
    }

    public void fetchNextBatch() {
        if (!running) return;
        if (currentBatch.size() >= 10) return; // Already have a full batch staged
        System.out.println("[SharedBuffer] Fetching next batch via HTTP...");
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mempoolApiUrl + "/api/mempool/batch"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200 && response.body() != null && !response.body().isEmpty()) {
                        JsonObject jsonResponse = GSON.fromJson(response.body(), JsonObject.class);
                        if (jsonResponse.has("transactions")) {
                            JsonArray txArray = jsonResponse.getAsJsonArray("transactions");

                            List<Transaction> batch = new ArrayList<>();
                            for (JsonElement element : txArray) {
                                Transaction tx = Transaction.fromJson(element.toString());
                                if (tx != null && tx.getTxId() != null) {
                                    batch.add(tx);
                                }
                            }
                            currentBatch = batch;
                            System.out.println("[SharedBuffer] Batch ready: " + batch.size() + " transactions staged.");
                        } else {
                            System.out.println("[SharedBuffer] Batch JSON missing transactions array.");
                        }
                    } else {
                        System.out.println("[SharedBuffer] No batch available or non-200 status: " + response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    System.err.println("[SharedBuffer] fetchNextBatch async error: " + e.getMessage());
                    return null;
                });
        } catch (Exception e) {
            System.err.println("[SharedBuffer] fetchNextBatch error: " + e.getMessage());
        }
    }

    public List<Transaction> getCurrentBatch() { return Collections.unmodifiableList(currentBatch); }
    public boolean isEmpty()   { return currentBatch.isEmpty(); }
    public int getBatchSize()  { return currentBatch.size(); }

    public void flush() {
        currentBatch = new ArrayList<>();
        System.out.println("[SharedBuffer] Staging area flushed.");
    }

    public void fetchNextBatchSync() {
        if (!running) return;
        if (currentBatch.size() >= 10) return;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mempoolApiUrl + "/api/mempool/batch"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null && !response.body().isEmpty()) {
                JsonObject jsonResponse = GSON.fromJson(response.body(), JsonObject.class);
                if (jsonResponse.has("transactions")) {
                    JsonArray txArray = jsonResponse.getAsJsonArray("transactions");

                    List<Transaction> batch = new ArrayList<>();
                    for (JsonElement element : txArray) {
                        Transaction tx = Transaction.fromJson(element.toString());
                        if (tx != null && tx.getTxId() != null) {
                            batch.add(tx);
                        }
                    }
                    currentBatch = batch;
                }
            }
        } catch (Exception e) {
            System.err.println("[SharedBuffer] fetchNextBatchSync error: " + e.getMessage());
        }
    }
}
