package app;

import crypto.Ed25519Util;
import model.Block;
import model.BlockHeader;
import model.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * GenesisConfig — Deterministic bootstrap configuration for the chain.
 *
 * Integration Plan §1.1:
 *  - Defines round 0 (genesis block) with a hardcoded, deterministic Seed.
 *  - Defines all initial validator accounts with balances and stakes.
 *  - The genesis block is the cryptographic anchor of the entire chain.
 *
 * Fixes applied:
 *
 *  FIX #2 — Deterministic Genesis Timestamp:
 *   The genesis block no longer uses System.currentTimeMillis(). Instead it uses
 *   a hardcoded constant GENESIS_TIMESTAMP. If every node calculates the genesis
 *   block independently, they MUST all arrive at the identical hash. A live
 *   timestamp made this impossible since each node would start at a different ms.
 *
 *  FIX #3/#4 — Genesis Stake as Historical Transactions:
 *   Genesis accounts' initial stakes are now represented as synthetic STAKE
 *   transactions committed in round 0. This means getOnlineStakeAtRound(lookback)
 *   and getAddressStakeAtRound(pubKey, lookback) in DatabaseManager will find
 *   this data when the lookback round is >= 0 (which it always is). Without this,
 *   no node can ever win sortition at round 1 because the historical stake query
 *   finds zero stake (no STAKE transactions exist yet).
 *
 *  FIX #5/#6 — System Key for DEPOSIT Transactions:
 *   A deterministic SYSTEM_PUB_KEY is introduced. DEPOSIT transactions MUST be
 *   signed by the holder of the corresponding SYSTEM_PRIV_KEY to prevent any
 *   user from depositing arbitrary amounts to themselves. The PendingStateOverlay
 *   rejects DEPOSIT transactions not signed by this key.
 */
public class GenesisConfig {

    // -------------------------------------------------------------------------
    // Deterministic Genesis Constants (FIX #2)
    // -------------------------------------------------------------------------

    /**
     * FIX #2: Hardcoded genesis timestamp so all nodes produce an identical genesis hash.
     * Value chosen as a fixed epoch — not a live system clock call.
     * January 1, 2025 00:00:00 UTC in milliseconds.
     */
    public static final long GENESIS_TIMESTAMP = 1_735_689_600_000L;

    /**
     * The initial Randomness Beacon seed (public constant, hardcoded).
     * Used as VRF_Input for round 1's Phase 1 lottery.
     * Chosen as SHA-256("ALGORAND_GENESIS_V1") for auditability.
     */
    public static final String GENESIS_SEED = Ed25519Util.sha256Hex("ALGORAND_GENESIS_V1");

    /**
     * The genesis block hash. Computed deterministically from the genesis header.
     * Set once by buildGenesisBlock() and cached here.
     */
    public static String GENESIS_HASH;

    // -------------------------------------------------------------------------
    // FIX #5/#6 — System Key for DEPOSIT operations
    // -------------------------------------------------------------------------

    /**
     * FIX #5/#6: Deterministic system Ed25519 key pair for DEPOSIT transactions.
     * All DEPOSIT transactions on-chain MUST be signed by SYSTEM_PRIV_KEY.
     * The PendingStateOverlay validates this: any DEPOSIT from a different sender
     * is immediately rejected with TXN_REJECTED (Invalid Deposit Authority).
     *
     * Key generated deterministically from the phrase "SYSTEM_DEPOSIT_KEY_V1"
     * so all nodes agree on which public key is the system key.
     */
    public static final String SYSTEM_PUB_KEY;
    public static final org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters SYSTEM_PRIV_KEY;
    public static final String SYSTEM_ADDRESS;

    static {
        // Derive system key from deterministic seed
        byte[] systemSeed = new byte[32];
        String hexSeed = Ed25519Util.sha256Hex("SYSTEM_DEPOSIT_KEY_V1");
        for (int i = 0; i < 32; i++) {
            systemSeed[i] = (byte) Integer.parseInt(hexSeed.substring(i * 2, i * 2 + 2), 16);
        }
        org.bouncycastle.crypto.AsymmetricCipherKeyPair kp =
            Ed25519Util.generateKeyPairFromSeed(systemSeed);
        SYSTEM_PRIV_KEY = (org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters) kp.getPrivate();
        SYSTEM_PUB_KEY  = Ed25519Util.encodePublicKey(
            (org.bouncycastle.crypto.params.Ed25519PublicKeyParameters) kp.getPublic());
        SYSTEM_ADDRESS = Ed25519Util.deriveAddress(SYSTEM_PUB_KEY);
    }

    // -------------------------------------------------------------------------
    // Genesis Account Structure
    // -------------------------------------------------------------------------

    /**
     * A genesis account: a validator with initial balance and stake.
     */
    public static class GenesisAccount {
        /** 40-char hex on-chain address (SHA-256(pubKey)[0:40]). */
        public final String address;

        /** Base64-encoded Ed25519 public key. */
        public final String pubKey;

        /** Starting balance in token units. */
        public final double initialBalance;

        /** Starting stake in token units (activates as validator immediately). */
        public final double initialStake;

        public GenesisAccount(String address, String pubKey, double initialBalance, double initialStake) {
            this.address        = address;
            this.pubKey         = pubKey;
            this.initialBalance = initialBalance;
            this.initialStake   = initialStake;
        }
    }

    // -------------------------------------------------------------------------
    // Network Configuration
    // -------------------------------------------------------------------------

    /**
     * Generates the deterministic genesis accounts for the testbed network.
     *
     * FIX #3/#4: Genesis stake is also injected as synthetic STAKE transactions
     * in round 0 (via buildGenesisSyntheticStakeTransactions). This allows the
     * historical stake queries (getOnlineStakeAtRound, getAddressStakeAtRound)
     * to find stake data for these validators at any lookback round >= 0.
     *
     * @param nodeCount Number of genesis validator nodes to pre-register.
     * @return List of genesis accounts.
     */
    public static List<GenesisAccount> getGenesisAccounts(int nodeCount) {
        List<GenesisAccount> accounts = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            Wallet w = Wallet.fromSeedPhrase("GENESIS_NODE_" + i);
            double balance = (i <= 5) ? 10_000.0 : 0.0;
            double stake   = (i <= 5) ? 5_000.0 : 0.0;
            accounts.add(new GenesisAccount(
                w.getAddress(),
                w.getPublicKeyBase64(),
                balance,
                stake
            ));
        }
        return accounts;
    }

    /**
     * FIX #3/#4: Builds synthetic STAKE transactions representing genesis validators'
     * initial stakes. These are stored in the transactions table at block_round=0.
     *
     * Without these synthetic transactions, the historical stake queries that look
     * back using getAddressStakeAtRound(pubKey, R-320) return 0 for all addresses,
     * making it mathematically impossible for any node to win sortition in round 1.
     *
     * These transactions are NOT signed by real private keys (they use a GENESIS_SIG
     * placeholder) and are NOT verified by PendingStateOverlay. They are only ever
     * inserted at genesis time by DatabaseManager.injectGenesis().
     *
     * @param accounts List of genesis accounts whose stakes need historical records.
     * @return List of synthetic STAKE transactions for round 0.
     */
    public static List<Transaction> buildGenesisSyntheticStakeTransactions(List<GenesisAccount> accounts) {
        List<Transaction> syntheticTxs = new ArrayList<>();
        for (GenesisAccount acct : accounts) {
            if (acct.initialStake <= 0) continue;

            Transaction tx = new Transaction();
            tx.setType(Transaction.Type.STAKE);
            tx.setSenderPubKey(acct.pubKey);
            tx.setReceiverPubKey(null);
            tx.setAmount(acct.initialStake);
            tx.setFirstValid(0);
            tx.setLastValid(Long.MAX_VALUE);
            tx.setTimestamp(GENESIS_TIMESTAMP);
            tx.setNote("GENESIS_SYNTHETIC_STAKE");

            // txId is SHA-256(signable data) — no private key needed for genesis synthetic txs
            String txId = tx.computeTxId();
            tx.setTxId(txId);
            // Synthetic signature placeholder — never verified for genesis bootstrap
            tx.setEd25519Signature("GENESIS_SYNTHETIC");

            syntheticTxs.add(tx);
        }
        return syntheticTxs;
    }

    // -------------------------------------------------------------------------
    // Genesis Block Construction (FIX #2)
    // -------------------------------------------------------------------------

    /**
     * Constructs the deterministic genesis block (round 0).
     *
     * FIX #2: Uses GENESIS_TIMESTAMP instead of System.currentTimeMillis().
     * All nodes in the network call this method and MUST produce an identical hash.
     * If any field is non-deterministic (live timestamp, random nonce) the genesis
     * hashes diverge and every cross-node block validation fails forever.
     *
     * FIX #66: All fields that go into getSignableData() are set BEFORE signing.
     * In this case there is no real Ed25519 signing (genesis uses a GENESIS placeholder)
     * but the hash is deterministically computed from all fixed fields.
     *
     * @return The deterministic genesis Block.
     */
    public static Block buildGenesisBlock() {
        BlockHeader header = new BlockHeader();
        header.setRound(0);
        header.setTimestamp(GENESIS_TIMESTAMP);        // FIX #2: constant, not System.currentTimeMillis()
        header.setPreviousBlockHash(Block.BOTTOM_HASH); // No previous block
        header.setTransactionMerkleRoot(Block.BOTTOM_HASH);
        header.setProposerVRFProof("GENESIS");
        header.setProposerPubKey("GENESIS");
        header.setEd25519Signature("GENESIS");
        header.setSeed(GENESIS_SEED);

        // FIX #66: Merkle root and all fields are fully set before computing hash.
        // Hash = SHA-256(signable data + GENESIS_SEED) — fully deterministic.
        String genesisHash = Ed25519Util.sha256Hex(header.getSignableData() + GENESIS_SEED);
        header.setHash(genesisHash);
        GENESIS_HASH = genesisHash;

        Block genesis = new Block();
        genesis.setHeader(header);
        genesis.setTransactions(new ArrayList<>());
        return genesis;
    }

    // -------------------------------------------------------------------------
    // Seed Peers Configuration
    // -------------------------------------------------------------------------

    /**
     * Returns the list of seed relay node addresses for P2P bootstrapping.
     * Format: "host:port"
     *
     * @param nodeCount Number of nodes in the testbed.
     * @param myPort    This node's own port (excluded from the peers list).
     * @return List of "host:port" strings for seed peers.
     */
    public static List<String> getSeedPeers(int nodeCount, int myPort) {
        List<String> peers = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            int port = 9000 + i;
            if (port != myPort) {
                peers.add("localhost:" + port);
            }
        }
        return peers;
    }
}
