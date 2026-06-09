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
    private volatile long currentBatchId = -1L;

    private volatile List<Transaction> capturedBatch = null;
    private volatile long capturedBatchId = -1L;

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
        capturedBatch = new ArrayList<>(currentBatch);
        capturedBatchId = currentBatchId;
        return Collections.unmodifiableList(capturedBatch);
    }

    public synchronized void ackCurrentBatch() {
        resolveBatchInBackend("/api/mempool/confirm", "ACK (Commit Success)");
    }

    public synchronized void nackCurrentBatch() {
        resolveBatchInBackend("/api/mempool/requeue", "NACK (Round Bottom/Failure)");
    }

    private void resolveBatchInBackend(String endpoint, String reason) {
        if (capturedBatchId == -1L || capturedBatch == null || capturedBatch.isEmpty()) return;

        try {
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("batchId", capturedBatchId);
            String jsonBody = GSON.toJson(payload);

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(mempoolApiUrl + endpoint))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            final long idToConfirm = capturedBatchId;
            httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    System.out.println("[SharedBuffer] " + reason + " | Called POST " + endpoint + " for batchId=" +
                                       idToConfirm + " | Status=" + response.statusCode());
                })
                .exceptionally(e -> {
                    System.err.println("[SharedBuffer] Failed to resolve batch async: " + e.getMessage());
                    return null;
                });
        } catch (Exception e) {
            System.err.println("[SharedBuffer] Failed to resolve batch: " + e.getMessage());
        } finally {
            capturedBatch = null;
            capturedBatchId = -1L;
        }
    }

    public synchronized void onCertificateEvent(BlockCertificate cert) {
        if (cert.getRound() <= lastHandledRound) {
            System.out.println("[SharedBuffer] Duplicate CertificateEvent for round=" + cert.getRound() + " — ignored.");
            return;
        }
        lastHandledRound = cert.getRound();
        System.out.println("[SharedBuffer] CertificateEvent | round=" + cert.getRound() +
                           " | isBottom=" + cert.isBottom() +
                           " | flushing " + currentBatch.size() + " transactions");
        flush();
        if (running) {
            fetchNextBatch();
        }
    }

    private void flush() {
        currentBatch = new ArrayList<>();
        currentBatchId = -1L;
        System.out.println("[SharedBuffer] Staging area flushed.");
    }

    private void fetchNextBatch() {
        if (!running) return;
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
                        if (jsonResponse.has("batchId") && jsonResponse.has("transactions")) {
                            long batchId = jsonResponse.get("batchId").getAsLong();
                            JsonArray txArray = jsonResponse.getAsJsonArray("transactions");

                            List<Transaction> batch = new ArrayList<>();
                            for (JsonElement element : txArray) {
                                Transaction tx = Transaction.fromJson(element.toString());
                                if (tx != null && tx.getTxId() != null) {
                                    batch.add(tx);
                                }
                            }
                            currentBatch = batch;
                            currentBatchId = batchId;
                            System.out.println("[SharedBuffer] Batch " + batchId + " ready: " + batch.size() + " transactions staged.");
                        } else {
                            System.out.println("[SharedBuffer] Batch JSON missing batchId or transactions array.");
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
}
