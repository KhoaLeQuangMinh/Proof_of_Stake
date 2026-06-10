package buffer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import model.Transaction;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/**
 * EventGateway — Singleton deduplication gateway for all upstream webhooks.
 *
 * Integration Plan §3.2 (The Singleton Event Gateway):
 *  A dedicated deduplication class that acts as the single source of truth
 *  for upstream webhooks. It prevents race conditions where a transaction
 *  could receive multiple conflicting event notifications.
 */
public class EventGateway {

    private static final EventGateway INSTANCE = new EventGateway();
    private static final Gson GSON = new Gson();

    private String eventBusUrl;
    private Connection connection;
    private Channel channel;
    private final String queueName = "event_bus_queue";

    private final java.util.Map<String, Boolean> resolvedTransactions = new java.util.LinkedHashMap<String, Boolean>(1000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
            return size() > 10000; // Store last 10,000 transactions to prevent stale duplicate storms
        }
    };

    private EventGateway() {
        // Initialization happens in setEventBusUrl
    }

    public static EventGateway getInstance() {
        return INSTANCE;
    }

    public void setEventBusUrl(String url) {
        this.eventBusUrl = url;
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri(url);
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.queueDeclare(queueName, true, false, false, null);
            System.out.println("[EventGateway] Connected to RabbitMQ at " + url);
        } catch (Exception e) {
            System.err.println("[EventGateway] Failed to connect to RabbitMQ: " + e.getMessage());
        }
    }

    public synchronized void publishValidated(Transaction tx) {
        if (tx == null || tx.getTxId() == null) return;
        if (resolvedTransactions.putIfAbsent(tx.getTxId(), Boolean.TRUE) != null) {
            System.out.println("[EventGateway] Swallowed duplicate VALIDATED for txId=" + tx.getTxId().substring(0, 8));
            return;
        }

        System.out.println("[EventGateway] ✅ TXN_VALIDATED | txId=" + tx.getTxId());
        
        JsonObject payload = new JsonObject();
        payload.addProperty("txId", tx.getTxId());
        if (tx.getType() != null) payload.addProperty("type", tx.getType().name());
        payload.addProperty("amount", tx.getAmount());
        
        sendEvent("TXN_VALIDATED", payload, tx.getTxId());
    }

    public synchronized void publishRejectedInvalid(Transaction tx, String reason) {
        if (tx == null || tx.getTxId() == null) return;
        if (resolvedTransactions.putIfAbsent(tx.getTxId(), Boolean.TRUE) != null) {
            System.out.println("[EventGateway] Swallowed duplicate REJECTED(Invalid) for txId=" + tx.getTxId().substring(0, 8));
            return;
        }

        System.out.println("[EventGateway] ❌ TXN_REJECTED(Invalid) | txId=" + tx.getTxId() + " | reason=" + reason);
        
        JsonObject payload = new JsonObject();
        payload.addProperty("txId", tx.getTxId());
        if (tx.getType() != null) payload.addProperty("type", tx.getType().name());
        payload.addProperty("reason", reason);
        
        sendEvent("TXN_REJECTED", payload, tx.getTxId());
    }

    public synchronized void publishRejectedTimeout(List<Transaction> txs) {
        if (txs == null || txs.isEmpty()) return;

        int fired = 0;
        int swallowed = 0;

        for (Transaction tx : txs) {
            if (tx == null || tx.getTxId() == null) continue;
            if (resolvedTransactions.putIfAbsent(tx.getTxId(), Boolean.TRUE) != null) {
                swallowed++;
                continue;
            }

            System.out.println("[EventGateway] ⏱ TXN_REJECTED(Timeout) | txId=" + tx.getTxId());

            JsonObject payload = new JsonObject();
            payload.addProperty("txId", tx.getTxId());
            if (tx.getType() != null) payload.addProperty("type", tx.getType().name());
            payload.addProperty("reason", "NETWORK_TIMEOUT");
            
            sendEvent("TXN_REJECTED", payload, tx.getTxId());
            fired++;
        }

        System.out.println("[EventGateway] Timeout batch: fired=" + fired + " | deduplicated=" + swallowed);
    }

    private void sendEvent(String eventType, JsonObject payloadData, String txId) {
        if (channel == null) {
            System.err.println("[EventGateway] Cannot send event - RabbitMQ channel not configured.");
            return;
        }

        try {
            JsonObject eventJson = new JsonObject();
            
            // Deterministic UUID
            String deterministicString = txId + "_" + eventType;
            if (payloadData.has("reason")) {
                deterministicString += "_" + payloadData.get("reason").getAsString();
            }
            String eventId = UUID.nameUUIDFromBytes(deterministicString.getBytes()).toString();
            
            eventJson.addProperty("eventId", eventId);
            eventJson.addProperty("eventType", eventType);
            eventJson.addProperty("timestamp", System.currentTimeMillis());
            eventJson.add("payload", payloadData);

            String jsonBody = GSON.toJson(eventJson);

            channel.basicPublish("", queueName, null, jsonBody.getBytes("UTF-8"));
        } catch (Exception e) {
            System.err.println("[EventGateway] Failed to build/send EventBus message: " + e.getMessage());
        }
    }

    public synchronized void resetForNewRound() {
        // No longer aggressively clearing the map.
        // The LinkedHashMap acts as an LRU cache and evicts old transactions
        // naturally. This prevents duplicate event storms from stale blocks.
    }

    public int getResolvedCount() {
        return resolvedTransactions.size();
    }
}
