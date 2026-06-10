# PPOS Blockchain: Off-Chain Integration Specification

This document provides a comprehensive, technically detailed specification of every protocol, endpoint, and message format required to successfully integrate the off-chain backend architecture with the PPOS blockchain nodes.

---

## 1. Node Orchestrator API
The `NodeOrchestrator` is an independent, lightweight HTTP server responsible for dynamically spinning up new blockchain nodes as separate OS-level processes. 

### **Endpoint: Create Node**
Boots a new instance of `app.Main` and auto-assigns its P2P networking port, identity, and genesis stake routing.

- **URL:** `http://<orchestrator_host>:8080/orchestrate`
- **Method:** `POST`
- **Headers:** `Content-Type: application/json`
- **Request Body:** `{}` *(Empty JSON object. Future iterations may include parameters like `{"role": "validator"}`)*
- **Response Format:**
  ```json
  {
    "status": "success",
    "nodeIndex": 5,
    "port": 9005,
    "publicKey": "J3roBaunuFcOUAOD0KhgiRUCYialvKfiWs3+FbSgcas=",
    "address": "c01ef35c96301d5eb94b8c2d8372acd0de5ca059"
  }
  ```

> [!TIP]
> The backend should capture the `publicKey` and `address` from this response and save it to the PostgreSQL database to correctly link the cloud-hosted node to the user's web account.

---

## 2. Inbound Data: The RabbitMQ Mempool (`SharedBuffer`)
The blockchain node does *not* accept REST API calls for submitting transactions. Instead, the off-chain backend pushes transactions to a highly available RabbitMQ queue, which the blockchain nodes continuously pull from.

- **Protocol:** AMQP 0-9-1
- **Queue Name:** `mempool_queue`
- **Routing:** Direct delivery (round-robin across active validator nodes)
- **Batch Size:** Nodes consume `10` transactions per block round via `fetchNextBatchSync()`.

### **Transaction Payload Format**
The backend must push JSON strings to `mempool_queue` adhering to the following model:

```json
{
  "txId": "b1c2d3e4f5a6b7c8...",
  "type": "DEPOSIT",
  "senderPubKey": "IVk/jVu2VOyZqdmr6P6MfmHJcHrclMrYilREXYHCQeo=",
  "receiverPubKey": "lhAIU6xXcADyNDudvrOJQ5gZnyv9gXYNB6Sfw1Gp2cY=",
  "amount": 100.50,
  "timestamp": 1718029000000,
  "note": "Optional memo",
  "ed25519Signature": "base64_encoded_signature_string"
}
```

> [!IMPORTANT]
> The `txId` is deterministically computed by hashing the transaction fields. The backend MUST sign the transaction using the sender's Ed25519 private key *before* placing it in the RabbitMQ mempool.

---

## 3. Outbound Data: The RabbitMQ Event Bus (`EventGateway`)
As the blockchain network reaches consensus, it needs to inform the off-chain backend about the status of transactions (Success, Failure) and blocks (Rewards). 

- **Protocol:** AMQP 0-9-1
- **Queue Name:** `event_bus_queue`
- **Deduplication:** Managed internally by the `EventGateway` singleton to prevent duplicate events during BBA* gossip.

### **Event Envelope Schema**
All outbound events share a strictly typed outer envelope:

```json
{
  "eventId": "UUIDv3-Deterministic-Hash",
  "eventType": "TXN_VALIDATED | TXN_REJECTED | BLOCK_FINALIZED",
  "timestamp": 1718029255000,
  "payload": { ... }
}
```

---

### **Event 1: TXN_VALIDATED**
Fired when a transaction successfully passes signature validation, balance checks, and is formally committed to a block in the SQLite world state.

- **Routing Key / Type:** `TXN_VALIDATED`
- **Payload Schema:**
  ```json
  "payload": {
    "txId": "b1c2d3e4f5a6b7c8...",
    "type": "DEPOSIT",
    "amount": 100.50
  }
  ```

---

### **Event 2: TXN_REJECTED**
Fired when a transaction fails cryptographic validation, balance checks, or sits in the mempool too long (network timeout/partition).

- **Routing Key / Type:** `TXN_REJECTED`
- **Payload Schema:**
  ```json
  "payload": {
    "txId": "b1c2d3e4f5a6b7c8...",
    "type": "STAKE",
    "reason": "INSUFFICIENT_FUNDS"  // Or "INVALID_SIGNATURE", "NETWORK_TIMEOUT"
  }
  ```

---

### **Event 3: BLOCK_FINALIZED (Rewards)**
Fired precisely when the BBA* Consensus Engine finalizes a block. This event informs the off-chain backend *who* proposed the block, allowing the backend to calculate and issue rewards into the user's web account.

- **Routing Key / Type:** `BLOCK_FINALIZED`
- **Payload Schema:**
  ```json
  "payload": {
    "round": 4,
    "blockHash": "8be96403...",
    "transactionsIncluded": 10,
    "rewardAmount": 4.5, // 2% of the sum of all DONATE transactions in the block
    "proposer": {
      "publicKey": "V3/GhzOpeBwKKbd4q7pzMDlYkXF6C8S/0K99CzxRXkU=",
      "address": "85489593bd26c8821f4b66d98ac226ca4e7ee6c8"
    }
  }
  ```

> [!NOTE]
> The blockchain core makes NO state changes regarding rewards. It simply dynamically calculates the `rewardAmount` (which is exactly 2% of all `DONATE` transactions included in that specific block) and passes this `BLOCK_FINALIZED` event to the backend. The backend is 100% responsible for applying this exact PPOS token reward to the user's profile.

---

## 4. Off-Chain Integration Checklist
For the backend engineering team to successfully connect to this system, they must implement:
1. **RabbitMQ Consumer:** Listen on `event_bus_queue` for the 3 event types.
2. **RabbitMQ Publisher:** Publish serialized Ed25519-signed transactions to `mempool_queue`.
3. **HTTP Client:** Send POST requests to `http://<orchestrator_host>:8080/orchestrate` to provision cloud nodes.
4. **Database Syncing:** Map `publicKey` from orchestrator responses/events to user profiles in the off-chain PostgreSQL/MongoDB databases.
