# The NEW_BLOCKCHAIN Integrated Implementation Plan

This implementation plan rigorously translates the `algorand_end_to_end_architecture.md` blueprint into concrete software components, explicitly fused with the custom RabbitMQ Shared Buffer, EventBus webhooks, and domain-specific transaction logic from the local Java codebase.

## User Review Required

- Please review the exact integration points for the **Shared Buffer Active Listener** and the **EventBus Triggers** to ensure they align perfectly with your upstream architecture.

---

## 1. Core Data Structures

To prevent memory bloat and optimize the network, payloads are strictly defined. We use **Ed25519** for all cryptography (replacing ECDSA) to guarantee high-speed batch verification during voting phases.

### 1.1 Block and Transactions
- `Transaction`: 
  - `SenderPubKey`, `ReceiverPubKey`, `Amount`, `Fee`, `Ed25519_Signature`
  - **Type Enum:** `STAKE`, `UNSTAKE`, `DEPOSIT`, `WITHDRAW`, `DONATE`.
  - **Concurrency Control:** `FirstValid` and `LastValid` block counters (replacing `nonce_order` to eliminate concurrency bottlenecks).
- `BlockHeader`: 
  - `Round`, `Timestamp`, `PreviousBlockHash`, `TransactionMerkleRoot`, `ProposerVRFProof`
  - `Seed`: The chained Randomness Beacon (used as the global VRF input for the next round's lottery).
  - `ProposerPubKey` & `Ed25519_Signature`: The fast cryptographic signature of the header itself.
- `Block`: 
  - `BlockHeader` + `[]Transaction`

### 1.2 Consensus Messages
- `VoteMessage` (Polymorphic: represents `SoftVote`, `CertifyVote`, and `NextVote` / `CommonCoin`):
  - `Round`, `Step`, `SenderPubKey`
  - `Choice`: Binary (`BlockHash` or `Bottom`)
  - `VRFProof`: Cryptographic proof of winning the sortition lottery
  - `Ed25519_Signature`: Fast network identity verification
- `BlockCertificate`:
  - `Round`, `BlockHash`
  - `[]Ed25519_Signature` (A collection of $>2/3$ committee signatures)

---

## 2. Component 1: The Network Engine (P2P Layer)

The Network Engine runs on a dedicated background thread pool. It handles all raw socket I/O, routing, and cheap Sybil defenses. 
**Integration Note:** *P2P Transaction Gossiping has been entirely removed in favor of the Shared Buffer.*

### 2.1 State Variables
- `LRUCache<MessageID> seenMessages`: Stores `SHA256(message)` to prevent infinite routing loops.
- `int Network_Tip`: Passively updated by observing the highest valid round in the gossip stream.
- `Map<Round, Map<Step, List<Message>>> ForwardCache`: The live memory buffer.

### 2.2 Core Functions
- `start()`: Establishes persistent connections to 4-8 Relay Nodes. 
- `ingressLoop(byte[] rawPayload)`:
  - **Step 0 (DDoS Protection):** Check IP rate limits.
  - **Step 1 (Payload Hash):** Hash payload to create `MessageID`. If `seenMessages.contains`, instantly `DROP`.
  - **Step 2 (Cryptography):** Fast `Ed25519` signature check.
  - **Step 3 (Proposer Equivocation Defense):** Instantly drop concurrent proposals from the same sender.
  - **Step 4 (Halting Interrupt):** If message is a `BlockCertificate`, trigger `SystemInterrupt(CertificateEvent)`.
  - **Step 5 (10-Block Rule):** For Consensus Messages: If `Message.Round > Network_Tip + 10`, `DROP`.
  - **Step 6:** Push valid `VoteMessage` to `ForwardCache.put(Round, Step, Message)`.
- `gossip(Message msg)`: Serializes, signs, and pushes the message to all connected Relay Nodes.
- `activeFetch(startRound, endRound)`: Syncs missing block payloads from Relays.

---

## 3. Component 2: The Shared Buffer & Event Gateway (Sequencer Integration)

*This is a standalone infrastructure tier running alongside the network.*

### 3.1 The Shared Buffer
- **Input:** Pulls up to 10 transactions from RabbitMQ (or waits 5 seconds) into a staging area.
- **Active Listener (Flush):** The Buffer actively listens for the `SystemInterrupt(CertificateEvent)` fired by the Network Engine at the end of Phase 6.
  - Upon hearing this event, it instantly **flushes** the staging area and pulls the next batch from RabbitMQ. 
  - *(Note: The Buffer does NOT fire EventBus webhooks itself to prevent race conditions).*

### 3.2 The Singleton Event Gateway
- A dedicated deduplication class that acts as the single source of truth for upstream webhooks. It maintains a round-specific cache: `Map<TxId, Boolean> resolvedTransactions`.
- **Phase 2 (Sandbox) Hook:** If a transaction fails validation, the State Engine calls the Gateway. The Gateway checks the cache, fires `TXN_REJECTED (Invalid)`, and marks the `TxId` as resolved.
- **Phase 6 (Commit) Hook:** If a block is committed, the State Engine calls the Gateway. The Gateway fires `TXN_VALIDATED` and marks the `TxId` as resolved.
- **Phase 6 (Timeout) Hook:** If the round ends in `Bottom`, the Consensus Engine peeks at the Buffer's 10 transactions and calls the Gateway. The Gateway fires `TXN_REJECTED (Network Timeout)` **ONLY** for transactions that were not already marked as resolved in Phase 2.

---

## 4. Component 3: The State Engine (Ledger)

The State Engine manages the SQLite database, the VRF math, and the EventBus hooks.

### 4.1 State Variables
- `DatabaseManager ledger`: SQLite database for final ACID commits.
- `Pending State Overlay`: An in-memory HashMap used to simulate transactions fast without touching the physical disk.

### 4.2 Core Functions
- `getOnlineStake(lookbackRound)`: Queries the ledger precisely 320 blocks in the past to prevent flash-loan voting. 
- `verifyVRFStake(senderPubKey, vrfProof, lookbackRound)`: Executes the Binomial Distribution equation.
- `verifyCertificate(Certificate cert, Round R)`: Iterates through all `CertifyVote` signatures summing weights. Returns true ONLY if the total weight strictly exceeds $2/3$ of the Expected Committee.
- `simulateBlock(Block b)`:
  - Creates the **Pending State Overlay** from SQLite. Attempts to execute all 10 transactions in RAM.
  - **EventBus Hook:** If a transaction fails math or drops balance below 0, immediately trigger `EventBus.publish("TXN_REJECTED", reason="INVALID_MATH_OR_BALANCE")`.
  - Returns `true` if mathematically valid. The physical SQLite ledger MUST NOT be mutated here.
- `applyBlock(Block b)`:
  - *Atomic Commit:* Executes transactions using SQLite `conn.setAutoCommit(false)`.
  - *Custom Transaction Logic:* Executes the specific logic for `STAKE`, `UNSTAKE`, `DEPOSIT`, `WITHDRAW`.
  - *DONATE Logic:* For `DONATE` transactions, deducts the 2% proposer fee and credits it to `b.ProposerAddress`.
  - **EventBus Hook:** For every transaction that successfully commits to SQLite, trigger `EventBus.publish("TXN_VALIDATED")`.

---

## 5. Component 4: The Consensus Engine (State Machine)

The Consensus Engine replaces hardcoded `Thread.sleep()` with event-driven dynamic loops.

### 5.1 The Main Loop
- `while (true)`:
  - `handleCatchup()`
  - `executeRound(Current_Round)`

### 5.2 Round Execution (`executeRound(int N)`)

**Phase 1: Value Propose**
- `VRF_Input = Ledger.getPreviousBlock().Seed`.
- Run local VRF: `VRF_Hash, VRF_Proof = VRF(Node_Private_Key, VRF_Input)`.
- *Sortition Math:* **(Hardcoded 14 removed)**. Use the Binomial Distribution CDF: $B(k; w, p)$. The expected committee size is ~20 for proposers.
- If $k > 0$ (Won at least 1 seat): Construct `Block`. 
  - **Integration:** Pull the 10 transactions directly from the **Shared Buffer**.
  - Include the new `Seed` in the header. 
  - `NetworkEngine.gossip(BlockHeader)`.

**Phase 2: Pre-Validation & Minimum Hash**
- Start `DynamicAdaptiveTimeout(EMA)` based on network propagation speed.
- Pull all collected proposals from `ForwardCache`.
- Sort valid headers by VRF Hash to find the `Lowest VRF Hash`.
- `StateEngine.simulate(payload_of_lowest_hash)`. If this payload contains fake transactions and fails, discard it, ban the `ProposerPubKey`, and simulate the *second* lowest hash.
- `Best_Proposal = The lowest valid Hash that passes simulation`.
- If timeout fires and 0 valid proposals exist: `Best_Proposal = Bottom`.

**Phase 3: Filter (Proposer Committee Election)**
- Run local VRF: `VRF_Hash, VRF_Proof = VRF(Node_Private_Key, VRF_Input)`.
- Call `verifyVRFStake` to check if elected. (Target Expected Committee = ~1000).
- If won: `NetworkEngine.gossip(VoteMessage(Best_Proposal))`.

**Phase 4: Resolving Filter (Liveness Check)**
- Start `DynamicAdaptiveTimeout(EMA)`. 
- Count votes from `ForwardCache`. Track split votes. 
- *Dynamic Threshold:* **(Hardcoded 14 removed)**. A vote passes ONLY if a specific Hash hits $> 68\%$ of the *Expected Committee Size*.
- **If any Hash hits $> 68\%$ supermajority before timeout:** `BBA_Choice = Winning_Hash`.
- **If timeout fires:** `BBA_Choice = Bottom`.

**Phase 5: Binary Byzantine Agreement (BBA* Micro-Loop)**
- `while (true)`:
  - **Step A (Gossip):** `NetworkEngine.gossip(VoteMessage(BBA_Choice))`.
  - **Step B (Count):** If any specific Hash hits $> 68\% \rightarrow Final\_Winner = Winning\_Hash$. `break;` If Bottom hits $> 68\% \rightarrow Final\_Winner = Bottom$. `break;`
  - **Step C (Common Coin Deadlock Breaker):**
    - `Global_Coin = Lowest_VRF_Hash(Valid_VRF_Coins)`.
    - If `Global_Coin` is Even (Least Significant Bit == 0): `BBA_Choice = Hash X`.
    - If `Global_Coin` is Odd (Least Significant Bit == 1): `BBA_Choice = Bottom`.

**Phase 6: The Halting Condition (Certificate Assembly & Interrupt)**
- `NetworkEngine.gossip(CertifyVote(Final_Winner))`.
- **The Interrupt:** The `ConsensusEngine` thread blocks and waits for `SystemInterrupt(CertificateEvent)`.
- Once triggered:
  - *Bottom Exception:* If `Certificate.BlockHash == Bottom`, the round failed. Do NOT fetch a payload. Create an Empty Block, write it to the Ledger, and skip to `Current_Round++`. (This action triggers the Buffer's Active Listener to flush and fire `TXN_REJECTED` for the timeout).
  - *Network Resilience:* If not Bottom, secure the payload via `NetworkEngine.activeFetch()`.
  - `StateEngine.applyBlock(Payload)`. (This action fires `TXN_VALIDATED` hooks).
  - `Current_Round++`.
  - Loop immediately returns to Phase 1.
