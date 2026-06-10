#!/bin/bash
perl -0777 -pi -e 's/        if \(\!currentBatch\.isEmpty\(\)\) return; \/\/ Already have a batch staged, wait for it to be captured and acked/        if (currentBatch.size() >= 10) return; \/\/ Already have a full batch staged/g' src/main/java/buffer/SharedBuffer.java

cat << 'EOF2' >> src/main/java/buffer/SharedBuffer.java

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
                }
            }
        } catch (Exception e) {
            System.err.println("[SharedBuffer] fetchNextBatchSync error: " + e.getMessage());
        }
    }
EOF2

perl -0777 -pi -e 's/    public synchronized List<Transaction> captureRoundBatch\(\) \{\n        capturedBatch = new ArrayList<>\(currentBatch\);\n        capturedBatchId = currentBatchId;\n        return Collections\.unmodifiableList\(capturedBatch\);\n    \}/    public synchronized List<Transaction> captureRoundBatch() {\n        long start = System.currentTimeMillis();\n        while (System.currentTimeMillis() - start < 5000) {\n            fetchNextBatchSync();\n            if (currentBatch.size() >= 10) {\n                break;\n            }\n            try {\n                Thread.sleep(500);\n            } catch (InterruptedException e) {\n                Thread.currentThread().interrupt();\n                break;\n            }\n        }\n        capturedBatch = new ArrayList<>(currentBatch);\n        capturedBatchId = currentBatchId;\n        return Collections.unmodifiableList(capturedBatch);\n    }/g' src/main/java/buffer/SharedBuffer.java

