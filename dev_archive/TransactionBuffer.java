package com.tcl.backend.messaging.mempool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.tcl.backend.config.RabbitConfig;
import com.tcl.backend.model.OutboundTransactionMessage;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

// Coordinator between mempool_queue and blockchain nodes.
// Every 10 seconds, it pulls up to 10 transactions and enqueues them as a MempoolBatch.
// Blockchain nodes call getOldestBatch() to retrieve transactions for a PoS round.
// The proposer calls confirmBatch() after successfully closing a block.
@Component
public class TransactionBuffer {
    private static final int BATCH_SIZE = 10;
    private static final int MAX_QUEUE = 4;
    private final LinkedBlockingQueue<MempoolBatch> batchQueue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final ConnectionFactory connectionFactory;
    // Jackson ObjectMapper to deserialize raw JSON bytes → OutboundTransactionMessage
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Spring injects the ConnectionFactory bean configured in application.properties
    public TransactionBuffer(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    // ─── Scheduled Fetch ─────────────────────────────────────────────────────
    // Runs every 10 seconds AFTER the previous execution finishes (fixedDelay).
    // Pulls up to BATCH_SIZE transactions from mempool_queue and enqueues them.
    @Scheduled(fixedDelay = 10000)
    public void fetch() {
        // Back-pressure: if queue already has 4 batches waiting, skip this round
        if (batchQueue.size() >= MAX_QUEUE) {
            System.out.println("[BUFFER] Queue full (4/4). Skipping fetch.");
            return;
        }
        try {
            // Step 1: Open a Connection and a Channel
            // This Channel stays open until confirmBatch() ACKs and closes it.
            Connection connection = connectionFactory.createConnection();
            Channel channel = connection.createChannel(false); // false = transactional off
            List<OutboundTransactionMessage> transactions = new ArrayList<>();
            List<Long> deliveryTags = new ArrayList<>();

            // Step 2: Pull up to BATCH_SIZE messages (manual pull mode via basicGet)
            for (int i = 0; i < BATCH_SIZE; i++) {
                // basicGet(queue, autoAck=false) → returns null if queue is empty
                GetResponse response = channel.basicGet(RabbitConfig.MEMPOOL_QUEUE, false);
                if (response == null) {
                    break; // No more messages right now
                }
                // Deserialize raw JSON bytes into a transaction object
                OutboundTransactionMessage tx = objectMapper.readValue(
                        response.getBody(),
                        OutboundTransactionMessage.class
                );
                transactions.add(tx);
                // Save delivery tag — needed later to ACK this specific message
                deliveryTags.add(response.getEnvelope().getDeliveryTag());
            }

            // Step 3: If nothing was pulled, close the channel and return
            if (transactions.isEmpty()) {
                System.out.println("[BUFFER] Mempool is empty. Nothing to batch.");
                channel.close();
                return;
            }

            // Step 4: Wrap into a MempoolBatch and enqueue
            // The Channel stays open inside the batch — it will be closed in confirmBatch()
            MempoolBatch batch = new MempoolBatch(transactions, channel, deliveryTags);
            batchQueue.offer(batch);
            System.out.println("[BUFFER] Batch enqueued: " + transactions.size()
                    + " tx(s). Queue: " + batchQueue.size() + "/" + MAX_QUEUE);

        } catch (Exception e) {
            System.err.println("[BUFFER] fetch() failed: " + e.getMessage());
        }
    }

    // ─── Read ────────────────────────────────────────────────────────────────
    // Blockchain nodes call this to get the oldest (first-in) batch.
    // peek() reads without removing — the batch stays in queue until confirmed.
    public MempoolBatch getBatch() {
        return batchQueue.peek();
    }

    // ─── Confirm ─────────────────────────────────────────────────────────────
    // Called by the proposer after a block is successfully closed.
    // Removes the batch from the queue and ACKs all its transactions on the mempool.
    public void confirmBatch() {
        MempoolBatch batch = batchQueue.poll(); // removes HEAD from queue
        if (batch == null) {
            System.out.println("[BUFFER] confirmBatch() called but queue is empty.");
            return;
        }
        try {
            // basicAck(deliveryTag, multiple=true):
            // multiple=true → ACKs ALL messages up to and including this tag on this channel.
            // Since we pulled them in order on the same channel, this ACKs the whole batch at once.
            long lastTag = batch.deliveryTags.get(batch.deliveryTags.size() - 1);
            batch.channel.basicAck(lastTag, true);
            batch.channel.close();
            System.out.println("[BUFFER] Batch confirmed. "
                    + batch.getTransactions().size() + " tx(s) burned from mempool.");
        } catch (Exception e) {
            System.err.println("[BUFFER] confirmBatch() failed: " + e.getMessage());
        }
    }
}
