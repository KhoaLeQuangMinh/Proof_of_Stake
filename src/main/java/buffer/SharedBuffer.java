package buffer;

import com.rabbitmq.client.*;
import com.google.gson.Gson;
import model.BlockCertificate;
import model.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * SharedBuffer — Component 2: The Centralized Transaction Sequencer.
 *
 * Integration Plan §3.1 (The Shared Buffer):
 *  - Input: Pulls up to 10 transactions from RabbitMQ (or waits 5 seconds
 *    if 10 are not available) into a staging area that the Proposer reads.
 *  - Active Listener (Flush): Listens for SystemInterrupt(CertificateEvent).
 *    When the ConsensusEngine completes a round (success or Bottom), the
 *    NetworkEngine fires the CertificateEvent and this buffer auto-flushes.
 *  - NOTE: The Buffer does NOT fire EventBus webhooks itself.
 *    That responsibility belongs exclusively to the Singleton EventGateway
 *    to prevent race conditions and duplicate event emissions.
 *
 * Transaction Lifecycle in the Buffer:
 *  1. Transactions arrive in RabbitMQ from upstream producers.
 *  2. SharedBuffer pulls up to 10 (or waits 5s) into currentBatch.
 *  3. A winning Proposer reads currentBatch via getCurrentBatch().
 *  4. After the round ends (Certificate received), flush() is called.
 *  5. The EventGateway handles TXN_REJECTED(Timeout) for unvalidated txs.
 *  6. The next batch is pulled from RabbitMQ for the next round.
 *
 * Integration with EventGateway (§3.2):
 *  When the round ends in Bottom (empty block), the ConsensusEngine calls
 *  EventGateway.publishRejectedTimeout(sharedBuffer.getCurrentBatch()).
 *  This is done by the ConsensusEngine — NOT by SharedBuffer — to maintain
 *  clean separation of responsibilities.
 */
public class SharedBuffer {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Maximum transactions to pull per batch (integrated plan: "up to 10"). */
    private static final int  MAX_BATCH_SIZE  = 10;

    /** Maximum time to wait for a full batch (integrated plan: "5 seconds timeout"). */
    private static final long BATCH_TIMEOUT_MS = 5_000L;

    // -------------------------------------------------------------------------
    // RabbitMQ Connection State
    // -------------------------------------------------------------------------

    private Connection       rabbitConnection;
    private Channel          rabbitChannel;
    private final String     rabbitHost;
    private final int        rabbitPort;
    private final String     queueName;
    private final EventGateway eventGateway;

    // -------------------------------------------------------------------------
    // Current Batch State
    // -------------------------------------------------------------------------

    /**
     * The active staging area — transactions pulled from RabbitMQ and waiting
     * to be included in the next consensus round.
     * Thread-safe: access is synchronized via the object monitor.
     */
    private volatile List<Transaction> currentBatch = new ArrayList<>();

    /** Flag indicating if the buffer is actively connected and running. */
    private volatile boolean running = false;

    private static final Gson GSON = new Gson();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SharedBuffer(String rabbitHost, int rabbitPort, String queueName, EventGateway eventGateway) {
        this.rabbitHost    = rabbitHost;
        this.rabbitPort    = rabbitPort;
        this.queueName     = queueName;
        this.eventGateway  = eventGateway;
    }

    // -------------------------------------------------------------------------
    // Connection Management
    // -------------------------------------------------------------------------

    /**
     * Establishes the RabbitMQ connection and declares the input queue.
     * Pulls the first batch immediately after connecting.
     *
     * @throws Exception if the RabbitMQ connection cannot be established.
     */
    public void connect() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitHost);
        factory.setPort(rabbitPort);
        factory.setConnectionTimeout(5000);
        factory.setAutomaticRecoveryEnabled(true);

        rabbitConnection = factory.newConnection("SharedBuffer");
        rabbitChannel    = rabbitConnection.createChannel();

        // Declare queue as durable to survive RabbitMQ restarts
        rabbitChannel.queueDeclare(queueName, true, false, false, null);

        running = true;
        System.out.println("[SharedBuffer] Connected to RabbitMQ " + rabbitHost + ":" +
                           rabbitPort + " queue=" + queueName);

        // Pull the first batch immediately
        fetchNextBatch();
    }

    /** Gracefully closes the RabbitMQ connection. */
    public void stop() {
        running = false;
        try {
            if (rabbitChannel != null && rabbitChannel.isOpen()) rabbitChannel.close();
            if (rabbitConnection != null && rabbitConnection.isOpen()) rabbitConnection.close();
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Active Listener — CertificateEvent (§3.1)
    // -------------------------------------------------------------------------

    /**
     * Active Listener: Called by the NetworkEngine the instant a CertificateEvent
     * (BlockCertificate) is received or assembled. This is the core mechanism that
     * auto-flushes the buffer when a round completes.
     *
     * Integration Plan §3.1:
     *  "The Buffer actively listens for the SystemInterrupt(CertificateEvent)
     *   fired by the Network Engine at the end of Phase 6. Upon hearing this
     *   event, it instantly flushes the staging area and pulls the next batch."
     *
     * IMPORTANT: This method does NOT fire any EventBus webhooks.
     *  EventBus notifications (TXN_REJECTED/TXN_VALIDATED) are the sole
     *  responsibility of the ConsensusEngine calling the EventGateway directly.
     *
     * @param cert The BlockCertificate that signals the end of a consensus round.
     *             May represent a successful block OR a Bottom (empty block) round.
     */
    public synchronized void onCertificateEvent(BlockCertificate cert) {
        System.out.println("[SharedBuffer] CertificateEvent received | round=" + cert.getRound() +
                           " | isBottom=" + cert.isBottom() +
                           " | flushing " + currentBatch.size() + " transactions");

        // Step 1: Flush the current batch (delete all staged transactions)
        flush();

        // Step 2: Pull the next batch from RabbitMQ for the next round
        if (running) {
            fetchNextBatch();
        }
    }

    // -------------------------------------------------------------------------
    // Batch Management
    // -------------------------------------------------------------------------

    /**
     * Flushes the current batch staging area, deleting all staged transactions.
     * This marks them as "consumed" for this round (either validated or rejected).
     */
    private void flush() {
        currentBatch = new ArrayList<>();
        System.out.println("[SharedBuffer] Staging area flushed.");
    }

    /**
     * Pulls up to MAX_BATCH_SIZE transactions from RabbitMQ into the staging area.
     * If fewer than MAX_BATCH_SIZE are available, waits up to BATCH_TIMEOUT_MS.
     *
     * Integration Plan §3.1:
     *  "Pulls up to 10 transactions from RabbitMQ (or waits 5 seconds) into
     *   a staging area that all nodes can access from."
     */
    private void fetchNextBatch() {
        List<Transaction> batch = new ArrayList<>();
        long deadline = System.currentTimeMillis() + BATCH_TIMEOUT_MS;

        System.out.println("[SharedBuffer] Fetching next batch (max=" + MAX_BATCH_SIZE +
                           ", timeout=" + BATCH_TIMEOUT_MS + "ms)...");

        while (batch.size() < MAX_BATCH_SIZE && System.currentTimeMillis() < deadline) {
            if (!running || rabbitChannel == null || !rabbitChannel.isOpen()) break;

            try {
                GetResponse response = rabbitChannel.basicGet(queueName, false);

                if (response == null) {
                    // No message available — wait briefly and retry
                    Thread.sleep(200);
                    continue;
                }

                // Deserialize the transaction
                String json = new String(response.getBody(), StandardCharsets.UTF_8);
                Transaction tx = Transaction.fromJson(json);

                if (tx != null && tx.getTxId() != null) {
                    batch.add(tx);
                    // Acknowledge the message so RabbitMQ removes it from the queue
                    rabbitChannel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                    System.out.println("[SharedBuffer] Pulled tx: " + tx.getTxId().substring(0, 8) +
                                       " | type=" + tx.getType() + " (" + batch.size() + "/" + MAX_BATCH_SIZE + ")");
                } else {
                    // Malformed message — discard
                    rabbitChannel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[SharedBuffer] fetchNextBatch error: " + e.getMessage());
                break;
            }
        }

        currentBatch = batch;
        System.out.println("[SharedBuffer] Batch ready: " + batch.size() + " transactions staged.");
    }

    // -------------------------------------------------------------------------
    // Read API (used by ConsensusEngine Phase 1 Proposer)
    // -------------------------------------------------------------------------

    /**
     * Returns the current batch of staged transactions.
     *
     * Integration Plan §5.2 Phase 1:
     *  "Pull the 10 transactions directly from the Shared Buffer."
     *  Called by the ConsensusEngine when a node wins the sortition lottery
     *  and becomes the block proposer.
     *
     * @return An unmodifiable view of the current staged transactions.
     *         May be empty if RabbitMQ is empty or the connection is down.
     */
    public List<Transaction> getCurrentBatch() {
        return Collections.unmodifiableList(currentBatch);
    }

    /**
     * Returns true if the buffer currently has no staged transactions.
     * A proposer should skip block construction if the buffer is empty.
     */
    public boolean isEmpty() {
        return currentBatch.isEmpty();
    }

    /** Returns the number of transactions currently staged. */
    public int getBatchSize() {
        return currentBatch.size();
    }
}
