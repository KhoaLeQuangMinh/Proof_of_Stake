# Distributed Blockchain Mock Testing Guide

This guide will walk you through exactly how to spin up your blockchain nodes locally alongside a mock Centralised Mempool and RabbitMQ EventBus.

**Zero code modifications have been made to the Java source files.** The system will run exactly as compiled.

## Prerequisites
Make sure you have Docker installed and running for RabbitMQ, and Python 3 installed for the mock mempool.

---

## Step 1: Start RabbitMQ (The EventBus)

Open a new terminal and run the following command to start RabbitMQ locally using Docker. This exposes the AMQP protocol on port `5672` and the management UI on `15672`.

```bash
docker run -d --name local-rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```
*You can view the RabbitMQ dashboard at [http://localhost:15672](http://localhost:15672) (Login: guest / guest).*

---

## Step 2: Start the Mock Mempool API

I have provided a Python 3 script `mock_mempool.py` in this folder that simulates the Spring Boot backend using zero external dependencies.

Open a second terminal, navigate to the `test` directory, and start the server:

```bash
cd /Users/khoale/Downloads/Distributed_System/test
python3 mock_mempool.py
```
*This server will listen on `http://localhost:8080`. Leave this terminal running.*

---

## Step 3: Compile the Blockchain (If needed)

In a third terminal, make sure the project is compiled:
```bash
cd /Users/khoale/Downloads/Distributed_System
mvn clean compile package
```

---

## Step 4: Start the Blockchain Nodes

You will now start a 5-node testbed. 
Because `Main.java` defaults to HTTP for the EventBus, **you must explicitly pass the RabbitMQ `amqp://` URI as the 4th argument.**

Open 5 separate terminal tabs (or split panes), and run one command in each:

**Node 0:**
```bash
cd /Users/khoale/Downloads/Distributed_System
java -jar target/blockchain.jar 0 5 http://localhost:8080 amqp://localhost:5672
```

**Node 1:**
```bash
cd /Users/khoale/Downloads/Distributed_System
java -jar target/blockchain.jar 1 5 http://localhost:8080 amqp://localhost:5672
```

**Node 2:**
```bash
cd /Users/khoale/Downloads/Distributed_System
java -jar target/blockchain.jar 2 5 http://localhost:8080 amqp://localhost:5672
```

**Node 3:**
```bash
cd /Users/khoale/Downloads/Distributed_System
java -jar target/blockchain.jar 3 5 http://localhost:8080 amqp://localhost:5672
```

**Node 4:**
```bash
cd /Users/khoale/Downloads/Distributed_System
java -jar target/blockchain.jar 4 5 http://localhost:8080 amqp://localhost:5672
```

*The nodes will automatically connect to each other, pull empty batches from your Python mempool mock, and begin churning empty blocks.*

---

## Step 5: Inject Test Transactions

While the nodes are running, you can manually push transactions into the mempool to see how the nodes react and watch the RabbitMQ queue light up.

Open a new terminal tab and run these `curl` commands:

### Test 1: A Valid DONATE Transaction
*(We use fake placeholder pub keys here. The simulation verifies math, not actual ED25519 signatures if the tx is synthetic from the mock).*

```bash
curl -X POST http://localhost:8080/inject -H "Content-Type: application/json" -d '{
  "txId": "TEST_DONATE_123",
  "type": "DONATE",
  "senderPubKey": "GENESIS_PUBKEY_PLACEHOLDER",
  "receiverPubKey": "TARGET_USER_PUBKEY",
  "amount": 50.0,
  "firstValid": 0,
  "lastValid": 999999,
  "timestamp": 1735689600000,
  "ed25519Signature": "MOCK_SIG"
}'
```
**Expected Result:**
1. The `SharedBuffer` pulls the batch.
2. The nodes process the `DONATE`.
3. RabbitMQ Management UI shows a single `TXN_VALIDATED` message.

### Test 2: An Unauthorized DEPOSIT (Security Check)
Let's see the nodes actively reject a `DEPOSIT` because the sender is not the `SYSTEM_PUB_KEY`.

```bash
curl -X POST http://localhost:8080/inject -H "Content-Type: application/json" -d '{
  "txId": "TEST_FRAUD_DEPOSIT",
  "type": "DEPOSIT",
  "senderPubKey": "MALICIOUS_USER_KEY",
  "receiverPubKey": "TARGET_USER_PUBKEY",
  "amount": 9999.0,
  "firstValid": 0,
  "lastValid": 999999,
  "timestamp": 1735689600000,
  "ed25519Signature": "MOCK_SIG"
}'
```
**Expected Result:**
1. The Proposer drops the transaction during Phase 1 simulation (`Unauthorized DEPOSIT attempt`).
2. Phase 6 fires `TXN_REJECTED (INVALID_TX)` to RabbitMQ.
